package com.polex.app

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
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
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
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
 * Home screen widget (1×1, ≈89 dp cell): a colored `N/M UP` hero with the
 * data-freshness line under it, then a two-column grid of server cells
 * (dot + name; offline = red dot + red name — the `off` label drops at 1×1
 * width). Data comes from the status cache kept fresh by [StatusWorker].
 *
 * Tight rows — how the padding between rows is fixed:
 *  - Glance 1.1.0's `Text`/`TextStyle` exposes **no `lineHeight`** and the
 *    underlying `glance_text` TextView keeps `includeFontPadding=true`, so
 *    each text row's line box is ≈1.4–1.5× the font size — the visible
 *    "padding between servers".
 *  - Negative padding is no help: Glance applies it via
 *    `RemoteViews.setViewPadding` and the host's `View.setPadding` clamps
 *    negative values to 0.
 *  - The stable-API lever is a **fixed height per row** ([heroHeight],
 *    [freshHeight], [cellHeight]): the Row/Text keeps its wrap-width but is
 *    constrained vertically, so the Column advances by the tight height
 *    instead of the full line box (glyphs stay clear: the line-box padding
 *    that would collide overflows instead of pushing the next row down).
 *    Worst-case content is ≈82 dp in the ≈89 dp 1×1 cell.
 *
 * Glance notes:
 *  - Column allows at most 10 children: hero (1) + freshness (1) + 3 grid
 *    rows + overflow (1) = at most 6; the grid shows at most [MAX_GRID_CELLS]
 *    servers (a "+N" line for the rest), the app shows the full list.
 *  - Glance 1.1.0 `RowScope` has no `weight` (`defaultWeight` only shares
 *    space between cells, it can't cap name width), so the two cells anchor
 *    left/right with a filling spacer between them (option B's "centered
 *    spacer" cell shape); the app shows the full, aligned list.
 */
class ServerWidget : GlanceAppWidget() {

    companion object {
        /** 1×1 budget: hero + a two-column grid of up to 6 servers (3 rows). */
        private const val MAX_GRID_CELLS = 6

        /** Visible grid cell name chars before the "…" (10sp Medium, half-cell width). */
        private const val CELL_NAME_MAX = 4

        // Tight row heights (see class KDoc). Sized to the 8/10/16 sp type:
        // ≈1.2× the font size, so the glyph span (≈0.9×) and its descenders
        // stay inside the row.
        private val heroHeight = 18.dp
        private val freshHeight = 10.dp
        private val cellHeight = 12.dp

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
        val shown = items.take(MAX_GRID_CELLS)
        val hidden = items.size - shown.size

        // Colored by the up/down verdict (green all-up, red otherwise).
        val verdictColor = ColorProvider(if (allUp) DotGreen else DotRed)
        val heroCountStyle = TextStyle(
            color = verdictColor,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
        val upWordStyle = TextStyle(
            color = verdictColor,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
        )
        val freshStyle = TextStyle(color = ColorProvider(TextDim), fontSize = 8.sp)
        val moreStyle = TextStyle(color = ColorProvider(TextDim), fontSize = 8.sp)
        val dimStyle = TextStyle(color = ColorProvider(TextDim), fontSize = 10.sp)

        provideContent {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(WidgetBg)
                    // Whole widget is tappable → opens the app.
                    .clickable(onClick = actionStartActivity(Intent(context, MainActivity::class.java)))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                if (items.isEmpty()) {
                    Text(
                        text = if (cacheTime > 0) "no data" else "no data yet",
                        modifier = GlanceModifier.height(cellHeight),
                        style = dimStyle,
                    )
                    if (cacheTime > 0) {
                        Text(
                            text = "updated ${timeOfDay(cacheTime)}",
                            modifier = GlanceModifier.height(freshHeight),
                            style = freshStyle,
                        )
                    }
                } else {
                    // hero: big colored N/M UP
                    Row(
                        modifier = GlanceModifier.height(heroHeight),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = "$upCount/${items.size}", style = heroCountStyle)
                        Spacer(GlanceModifier.width(3.dp))
                        Text(text = "UP", style = upWordStyle)
                    }
                    // data freshness under the hero
                    Text(
                        text = "updated ${timeOfDay(cacheTime)}",
                        modifier = GlanceModifier.height(freshHeight),
                        style = freshStyle,
                    )

                    // two-column grid of server cells (dot + name)
                    shown.chunked(2).forEach { pair ->
                        Row(
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .height(cellHeight),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            pair.forEach { s ->
                                cell(s)
                                if (s != pair.last()) Spacer(GlanceModifier.fillMaxWidth())
                            }
                        }
                    }
                    if (hidden > 0) {
                        Text(
                            text = "+$hidden more",
                            modifier = GlanceModifier.height(freshHeight),
                            style = moreStyle,
                        )
                    }
                }
            }
        }
    }

    /** One grid cell: colored dot + truncated name; offline styled red (no `off` label at 1×1). */
    @Composable
    private fun RowScope.cell(s: SrvStatus) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "●",
                style = TextStyle(
                    color = ColorProvider(if (s.online) DotGreen else DotRed),
                    fontSize = 8.sp,
                ),
            )
            Spacer(GlanceModifier.width(3.dp))
            Text(
                text = cellName(s.name),
                style = TextStyle(
                    color = ColorProvider(if (s.online) TextPrimary else DotRed),
                    fontWeight = FontWeight.Medium,
                    fontSize = 10.sp,
                ),
                maxLines = 1,
            )
        }
    }

    private fun cellName(name: String): String =
        if (name.length <= CELL_NAME_MAX) name else name.take(CELL_NAME_MAX) + "…"

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
