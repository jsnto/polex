package com.polex.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.TimeUnit

/**
 * Self-rescheduling inexact alarm (~15 min) that triggers a one-shot status
 * fetch for the widget.
 *
 * Why this exists on top of the WorkManager periodic job: the system may
 * defer/coalesce WorkManager periodic work far beyond its 15-minute
 * interval (doze, battery optimization), which in practice left the widget
 * stale until the app was opened. `setAndAllowWhileIdle` fires at roughly
 * the requested time even while the device is idle and needs no permission
 * (unlike exact alarms on Android 12+).
 *
 * The receiver re-arms the alarm after each tick (self-rescheduling
 * pattern), so a single schedule() call in MainActivity.onCreate covers
 * the lifetime of the app.
 */
object WidgetAlarm {
    private const val REQUEST_CODE = 1
    private val periodMs = TimeUnit.MINUTES.toMillis(15)

    fun schedule(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, WidgetRefreshReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        am.setAndAllowWhileIdle(
            AlarmManager.RTC,
            System.currentTimeMillis() + periodMs,
            pi,
        )
    }
}

class WidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        StatusWorker.enqueueNow(context)
        WidgetAlarm.schedule(context) // next tick
    }
}
