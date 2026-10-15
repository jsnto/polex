package com.polex.app

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.AppWidgetId
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
import kotlin.math.roundToInt

private val WidgetBg = Color(0xFF111827)
private val TextPrimary = Color(0xFFF9FAFB)
private val TextDim = Color(0xFF9CA3AF)
private val DotGreen = Color(0xFF4CAF50)
private val DotRed = Color(0xFFF44336)

/**
 * Home screen widget, offered at 1×1 and 2×1 (2 wide, 1 tall) on every
 * launcher, plus 1×2 and 2×2 on launchers with a row pitch under 80dp
 * (e.g. the Samsung S26 Ultra's One UI launcher, whose pitch is shorter
 * than the old 80dp height floor — see `res/xml/polex_widget_info.xml`
 * for the full offer matrix: 40×40dp floor, 190×80dp caps, horizontal
 * AND vertical resize): a colored `N/M UP` hero with the
 * data-freshness line under it, then a grid of server cells (dot + name;
 * offline = red dot + red name — the `off` label drops at 1×1 width). Data
 * comes from the status cache kept fresh by [StatusWorker].
 *
 * Display settings (Settings → Widget, stored in [Prefs], applied at
 * render time for every offered placement — 1×1/2×1 on every launcher,
 * plus 1×2/2×2 on short-row launchers):
 *  - grid columns: 2 (the default look) = dot + name pairs, one row of two
 *    cells; 1 = one row per server (see [widgetGridRows]);
 *  - which servers to show: the stored selection, `null` = all (the
 *    default); stale ids (removed servers) are dropped silently by
 *    [resolveWidgetServers] — never rendered, never a crash.
 * The hero counts only the servers actually shown (N/`shown.size` UP), so
 * a filtered widget stays internally consistent.
 *
 * The two-column grid reflows across the wider 2×1 cell with no layout
 * branching: each grid row is two equal `defaultWeight` halves (instead of
 * the old left/right anchors), so the same halves are tight at 1×1 and
 * widen with the 2×1 span. The name budget is always [CELL_NAME_MAX]
 * (4 chars) at both sizes: the launcher instance options carry the cell
 * POSITION (`cellX`, the 0-based column of the widget's top-left corner),
 * not its span, so a span-based budget can't be read from the stable
 * options API — the narrow budget is the only one guaranteed to fit every
 * placement. A placed widget is always at least one home-grid column wide
 * (≈94 dp on the Pixel 7 Pro, ≈89 dp on the A52 — the 40dp declared
 * minimum only affects the launcher PICKER's tile size, not the placed
 * widget), so the live type sizes fit with budget to spare.
 *
 * Tight rows — how the padding between rows is fixed:
 *  - Glance 1.1.0's `Text`/`TextStyle` exposes **no `lineHeight`** and the
 *    underlying `glance_text` TextView keeps `includeFontPadding=true`, so
 *    each text row's line box is ≈1.4–1.5× the font size — the visible
 *    "padding between servers".
 *  - Negative padding is no help: Glance applies it via
 *    `RemoteViews.setViewPadding` and the host's `View.setPadding` clamps
 *    negative values to 0.
 *  - The stable-API lever is a **fixed height per row** (the row-height dp
 *    constants from [WidgetContent], the single source of truth shared with
 *    the budget checks below): the Row/Text keeps its wrap-width but is
 *    constrained vertically, so the Column advances by the tight height
 *    instead of the full line box (glyphs stay clear: the line-box padding
 *    that would collide overflows instead of pushing the next row down).
 *    Worst-case content is 82 dp (3 rows + freshness + overflow) in the
 *    ≈89–116 dp 1×1 cells on the default-budget hosts (A52 ≈89 dp, Pixel 7
 *    Pro ≈116 dp — measured from a placed 1×1 widget's bounds) — the
 *    historical render.
 *
 * Short cells — the compact render when the host's ALLOCATION is too short
 * for the full layout (e.g. the Samsung S26 Ultra's One UI launcher, whose
 * documented row pitch is under 80 dp — pinned conservatively against the
 * 64 dp tight case, see [WidgetContent]): once the 40dp declared height
 * floor makes a 1-row placement possible there, a 1-row allocation is a
 * sub-80dp row, so the full 82 dp worst case no longer fits by
 * construction. The budget is the tightest of the host-reported candidate
 * allocations — API 31+ launchers push them into the stable widget
 * options (`OPTION_APPWIDGET_SIZES`, a list of px sizes, delivered via
 * `updateAppWidgetOptions`; Glance's receiver re-renders us on
 * `onAppWidgetOptionsChanged`) — so the span-agnostic fit is correct at
 * whatever size any launcher offers, with no host identification needed. Hosts that
 * don't report an allocation (pre-API 31, or a host that never pushes
 * sizes) get the 89 dp default budget: the full layout always fits there,
 * so their render is unchanged by this (the historical render). Per
 * render, [widgetVerticalLayout] fits the layout into the budget: the FULL
 * layout is kept when it fits; otherwise the COMPACT layout drops the
 * secondary freshness line (the hero verdict and the server grid carry the
 * state); if that still doesn't fit, grid rows drop from the bottom into
 * the "+N more" count.
 *
 * The fit is span-agnostic: the widget cannot read its own span from the
 * stable options API (only the cell position), so the layout is pinned to
 * the tightest 1-row cell and every offered size renders the same content
 * top-aligned (a 2-row allocation — short-row launchers — is 2×pitch ≥
 * 2×40dp = 80 dp: the fit rules keep the full layout when it fits (≥82 dp)
 * or drop to the 72 dp compact worst case (≤80 dp), so it always fits).
 *
 *
 * Glance notes:
 *  - Column allows at most 10 children: hero (1) + freshness (1) + at most
 *    3 grid rows (the per-mode [widgetMaxCells] budget) + overflow (1) =
 *    at most 6; the grid shows at most [widgetMaxCells] servers (a "+N"
 *    line for the rest), the app shows the full list.
 *  - Glance 1.1.0 `RowScope` has no `weight` (`defaultWeight` only shares
 *    space between cells, it can't cap name width), so the two-column grid
 *    is built from two equal `defaultWeight` half-Columns per row (the name
 *    budget above caps the name width instead); the app shows the full,
 *    aligned list.
 */
class ServerWidget : GlanceAppWidget() {

    companion object {
        /** Visible grid cell name chars before the "…" (10sp Medium, 1×1 half-cell width). */
        private const val CELL_NAME_MAX = 4

        // Tight row heights (see class KDoc) — the dp constants from
        // WidgetContent.kt (the single source of truth shared with the
        // short-cell budget checks), sized to the 8/10/16 sp type: ≈1.2×
        // the font size, so the glyph span (≈0.9×) and its descenders stay
        // inside the row.
        private val heroHeight = WIDGET_HERO_HEIGHT_DP.dp
        private val freshHeight = WIDGET_FRESHNESS_HEIGHT_DP.dp
        private val cellHeight = WIDGET_GRID_ROW_HEIGHT_DP.dp
        private val moreHeight = WIDGET_OVERFLOW_HEIGHT_DP.dp

        /** Refreshes all installed widget instances (call from the app/worker). */
        suspend fun refreshAll(context: Context) {
            ServerWidget().updateAll(context)
        }
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val cached = Prefs.cache(context)
        val live = runCatching { cached?.let { parseStatusArray(it) } }
            .getOrNull() ?: emptyList()
        // Display settings (Settings → Widget): which servers to show
        // (null = all; stale ids drop silently) and the grid's column mode.
        val columns = normalizeWidgetColumns(Prefs.widgetColumns(context))
        val items = resolveWidgetServers(live, Prefs.widgetSelectedServerIds(context))
        val upCount = items.count { it.online }
        val allUp = items.isNotEmpty() && upCount == items.size
        val cacheTime = Prefs.cacheTime(context)
        val rows = widgetGridRows(items, columns)
        val hidden = widgetOverflow(items, columns)

        // Short-cell fit (see the class KDoc and WidgetContent.kt): the
        // host's ALLOCATED height decides whether this render keeps the
        // full layout, drops the secondary freshness line, or drops grid
        // rows into the "+N more" count.
        val layout = widgetVerticalLayout(rows.size, hidden > 0, hostCellBudgetDp(context, id))
        val shownRows = rows.take(layout.gridRows)
        val shownHidden = items.size - shownRows.sumOf { it.size }

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
                    // data freshness under the hero — the SECONDARY line,
                    // dropped by the short-cell layout when the host's row
                    // pitch can't hold the full layout (see the class KDoc)
                    if (layout.showFreshness) {
                        Text(
                            text = "updated ${timeOfDay(cacheTime)}",
                            modifier = GlanceModifier.height(freshHeight),
                            style = freshStyle,
                        )
                    }

                    // Grid of server cells (dot + name). Two columns: each
                    // row is two equal weighted halves — RowScope has no
                    // per-cell `weight` (only `defaultWeight`), so a
                    // fill-width Column per column gives the even
                    // two-column split that reflows across the 2×1 span.
                    // One column: one row per server, full width.
                    shownRows.forEach { rowItems ->
                        Row(
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .height(cellHeight),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (columns == WIDGET_COLUMNS_1) {
                                cell(rowItems.first())
                            } else {
                                Column(GlanceModifier.defaultWeight()) {
                                    cell(rowItems.first())
                                }
                                if (rowItems.size > 1) {
                                    Column(GlanceModifier.defaultWeight()) {
                                        cell(rowItems.last())
                                    }
                                }
                            }
                        }
                    }
                    if (shownHidden > 0) {
                        Text(
                            text = "+$shownHidden more",
                            modifier = GlanceModifier.height(moreHeight),
                            style = moreStyle,
                        )
                    }
                }
            }
        }
    }

    /**
     * The vertical budget (dp) for the short-cell fit: the tightest of the
     * host-reported candidate allocations for this widget instance — read
     * from the stable widget options' `OPTION_APPWIDGET_SIZES` (a list of
     * px `SizeF` the launcher pushes via `updateAppWidgetOptions`, API 31+;
     * a multi-size list carries the landscape/portrait candidate
     * allocations of a responsive host, and the render must fit the
     * smallest of them). Hosts that
     * don't report an allocation (pre-API 31, a host that never pushes
     * sizes, or an error reading the options) get the 89 dp default
     * budget, which always fits the full layout (82 dp worst case) and
     * keeps the historical render. See WidgetContent.kt for the budgets
     * and the pinned arithmetic.
     */
    // `AppWidgetId` is the only `GlanceId` a `GlanceAppWidget` receives, but
    // it is `@RestrictTo(LIBRARY_GROUP)` in Glance 1.1.0 and there is no
    // stable API to read the widget id from the `GlanceId` — the cast is the
    // established workaround (lint: RestrictedApi).
    @Suppress("RestrictedApi")
    private fun hostCellBudgetDp(context: Context, glanceId: GlanceId): Int {
        val id = (glanceId as? AppWidgetId)?.appWidgetId
            ?: return WIDGET_HOST_BUDGET_DEFAULT_DP
        if (Build.VERSION.SDK_INT < 31) return WIDGET_HOST_BUDGET_DEFAULT_DP
        return try {
            @Suppress("DEPRECATION")
            val sizes = AppWidgetManager.getInstance(context)
                .getAppWidgetOptions(id)
                .getParcelableArrayList<android.util.SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            val minPx = sizes?.filter { it.height > 0f }?.minOfOrNull { it.height }
                ?: return WIDGET_HOST_BUDGET_DEFAULT_DP
            val density = context.resources.displayMetrics.density
            widgetBudgetDp((minPx / density).roundToInt())
        } catch (e: Exception) {
            WIDGET_HOST_BUDGET_DEFAULT_DP
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
                text = cellName(s.name, CELL_NAME_MAX),
                style = TextStyle(
                    color = ColorProvider(if (s.online) TextPrimary else DotRed),
                    fontWeight = FontWeight.Medium,
                    fontSize = 10.sp,
                ),
                maxLines = 1,
            )
        }
    }

    private fun cellName(name: String, max: Int): String =
        if (name.length <= max) name else name.take(max) + "…"

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
