package com.polex.app

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val WidgetBg = Color(0xFF111827)
private val TextPrimary = Color(0xFFF9FAFB)
private val TextDim = Color(0xFF9CA3AF)
private val DotGreen = Color(0xFF4CAF50)
private val DotRed = Color(0xFFF44336)

/**
 * Home screen widget: at-a-glance server status (name + online/offline dot).
 * Data comes from the status cache kept fresh by [StatusWorker] (periodic
 * WorkManager job + a one-shot fetch on every widget update tick).
 *
 * Glance notes:
 *  - Layout is a list of server rows (dot + name + latency) with a single
 *    bottom line combining the up count and data freshness, dot-separated
 *    ("2/3 up · updated 14:32").
 *  - Glance Column allows at most 10 children, so the list shows at most
 *    [MAX_SERVER_ROWS] servers (+ a "+N" line); the app shows the full list.
 */
class ServerWidget : GlanceAppWidget() {

    companion object {
        private const val MAX_SERVER_ROWS = 7

        /** Refreshes all installed widget instances (call from the app/worker). */
        suspend fun refreshAll(context: Context) {
            ServerWidget().updateAll(context)
        }
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val cached = Prefs.cache(context)
        val items = runCatching { cached?.let { parseStatusArray(it) } }
            .getOrNull() ?: emptyList()
        val upCount = items.count { it.online }
        val allUp = items.isNotEmpty() && upCount == items.size
        val cacheTime = Prefs.cacheTime(context)
        val shown = items.take(MAX_SERVER_ROWS)
        val hidden = items.size - shown.size

        provideContent {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(WidgetBg)
                    // Whole widget is tappable → opens the app.
                    .clickable(onClick = actionStartActivity(Intent(context, MainActivity::class.java)))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                if (items.isEmpty()) {
                    Text(
                        text = "waiting for first status…",
                        style = TextStyle(color = ColorProvider(TextDim), fontSize = 10.sp),
                    )
                } else {
                    // server list: one row per server
                    shown.forEach { s ->
                        Row(
                            modifier = GlanceModifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "●",
                                style = TextStyle(
                                    color = ColorProvider(if (s.online) DotGreen else DotRed),
                                    fontSize = 8.sp,
                                ),
                            )
                            Spacer(GlanceModifier.width(5.dp))
                            Text(
                                text = s.name,
                                style = TextStyle(
                                    color = ColorProvider(TextPrimary),
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 9.sp,
                                ),
                                maxLines = 1,
                            )
                            Spacer(GlanceModifier.fillMaxWidth())
                            Text(
                                text = if (s.online) "${s.latencyMs}ms" else "off",
                                style = TextStyle(
                                    color = ColorProvider(if (s.online) DotGreen else DotRed),
                                    fontSize = 8.sp,
                                ),
                            )
                        }
                    }
                    if (hidden > 0) {
                        Text(
                            text = "+$hidden more — tap to open app",
                            style = TextStyle(color = ColorProvider(TextDim), fontSize = 8.sp),
                        )
                    }
                }

                // bottom line: up count + data freshness, dot-separated
                Text(
                    text = when {
                        items.isEmpty() && cacheTime > 0 -> "no data · updated ${timeOfDay(cacheTime)}"
                        items.isEmpty() -> "no data yet"
                        else -> "$upCount/${items.size} up · updated ${timeOfDay(cacheTime)}"
                    },
                    modifier = GlanceModifier.padding(top = 1.dp),
                    style = TextStyle(
                        color = ColorProvider(if (allUp) DotGreen else DotRed),
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                    ),
                )
            }
        }
    }

    private fun timeOfDay(epochMillis: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMillis))
}

class ServerWidgetProvider : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget
        get() = ServerWidget()

    // The system calls onUpdate when the widget is placed and every
    // updatePeriodMillis (≥30 min). Fetch fresh status on each tick so the
    // widget doesn't stay empty just because the app isn't being opened —
    // the worker re-renders all instances once the data lands.
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        StatusWorker.enqueueNow(context)
    }
}
