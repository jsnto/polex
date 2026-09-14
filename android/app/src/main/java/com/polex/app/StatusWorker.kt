package com.polex.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/**
 * Fetches /api/status, caches the JSON (read by the widget) and pushes a
 * fresh glance to all PoleX widget instances.
 *
 * Runs on two triggers:
 *  - periodically every 15 min (WorkManager minimum) — scheduled in MainActivity
 *  - immediately as a one-shot when the widget is placed / gets a system
 *    update tick ([StatusWorker.enqueueNow] from ServerWidgetProvider.onUpdate)
 */
class StatusWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        /** Enqueue an immediate one-shot fetch (deduped via the unique work name). */
        fun enqueueNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<StatusWorker>().build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("polex-status-now", ExistingWorkPolicy.REPLACE, request)
        }
    }

    override suspend fun doWork(): Result {
        val appContext = applicationContext
        // Api.authToken is in-memory and only restored by MainActivity; the
        // worker often runs without the activity alive, so top it up here
        // or the status call 401s and the widget never refreshes.
        if (Api.authToken == null) {
            Api.authToken = Prefs.token(appContext)
        }
        val r = Api.safe { Api.get("${Prefs.base(appContext)}/api/status") }
        if (r.code == 200) {
            Prefs.setCache(appContext, r.body)
        }
        // Always refresh the widget: with new data or with the stale cache.
        ServerWidget.refreshAll(appContext)
        return Result.success()
    }
}
