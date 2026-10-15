package com.polex.app

/**
 * Widget display settings — the pure render logic (no Android imports, so
 * it is testable on the JVM without a device or Robolectric).
 *
 * The choices themselves live in [Prefs] (`widgetColumns` /
 * `widgetSelectedServerIds`); the settings screen (the "Widget" pane in
 * SettingsScreen) edits them and refreshes the widget on change;
 * [ServerWidget] applies them at render time for every offered placement
 * (1×1/2×1 everywhere, plus 1×2/2×2 on short-row launchers — the widget
 * cannot read its own span from the stable options API, so every rule
 * here must hold at the tightest size — a 1×1 cell):
 *
 *  - 2 columns (the default look) = dot + name pairs, two cells per row;
 *  - 1 column = one row per server;
 *  - server selection: `null` = all servers (the default), otherwise only
 *    the selected ids — a stale id (a server removed since) is dropped
 *    silently, never rendered.
 */

/** Server grid: one column (one row per server). */
const val WIDGET_COLUMNS_1 = 1

/** Server grid: two columns (dot + name pairs per row) — the default. */
const val WIDGET_COLUMNS_2 = 2

/** Default grid layout: the original two-column look. */
const val WIDGET_COLUMNS_DEFAULT = WIDGET_COLUMNS_2

/** Clamps any persisted raw value into the supported modes (2 when out of range). */
fun normalizeWidgetColumns(raw: Int): Int =
    if (raw == WIDGET_COLUMNS_1) WIDGET_COLUMNS_1 else WIDGET_COLUMNS_2

/**
 * Max servers rendered in the grid per column mode, budgeted so the
 * worst case — 18dp hero + 10dp freshness line + 3 rows at 12dp each +
 * the 10dp "+N more" line, plus the 8dp vertical padding (82dp —
 * [widgetLayoutHeightDp] with 3 rows, freshness and overflow) — fits the
 * default host budget ([WIDGET_HOST_BUDGET_DEFAULT_DP], the tightest
 * historically measured 1×1 cell, A52 ≈89 dp) in BOTH modes:
 *  - 2 columns: 6 cells = 3 rows (the original 1×1 budget);
 *  - 1 column: 3 servers = 3 rows (one row each).
 * Servers past the budget collapse into the "+N more" line; the app shows
 * the full list.
 */
fun widgetMaxCells(columns: Int): Int =
    if (normalizeWidgetColumns(columns) == WIDGET_COLUMNS_1) 3 else 6

/**
 * Which servers the widget renders, given the live (status-cache) list and
 * the stored selection: `selected == null` = all servers (the default),
 * otherwise the live servers whose id is in the selection. Order follows
 * the live list (the backend's server order), so a re-render after a
 * re-add keeps the same position. Stale ids — selected servers that no
 * longer exist — are dropped silently (filtering the LIVE list can never
 * render a removed server), so the widget never crashes on or renders a
 * stale entry.
 */
fun resolveWidgetServers(live: List<SrvStatus>, selected: Set<Int>?): List<SrvStatus> =
    if (selected == null) live else live.filter { selected.contains(it.id) }

/**
 * Grid rows for the render: 1 column = one row per server, 2 columns =
 * dot + name pairs (rows of two). The [widgetMaxCells] budget applies
 * first, so both modes stay inside the 1×1 vertical budget.
 */
fun widgetGridRows(items: List<SrvStatus>, columns: Int): List<List<SrvStatus>> {
    val shown = items.take(widgetMaxCells(normalizeWidgetColumns(columns)))
    return if (normalizeWidgetColumns(columns) == WIDGET_COLUMNS_1) shown.chunked(1) else shown.chunked(2)
}

/** Count of servers past the budget (the "+N more" line; 0 when all fit). */
fun widgetOverflow(items: List<SrvStatus>, columns: Int): Int =
    (items.size - widgetMaxCells(normalizeWidgetColumns(columns))).coerceAtLeast(0)

// ---------------------------------------------------------------------------
// Short-cell vertical fit (the 1-row placement every launcher can now make
// possible with the 40dp declared height floor — see polex_widget_info.xml).
//
// The budget is the height the HOST ACTUALLY ALLOCATED to the widget:
// launchers on API 31+ push their allocation into the stable widget
// options (`OPTION_APPWIDGET_SIZES`, a list of px sizes, delivered via
// `updateAppWidgetOptions` / `onAppWidgetOptionsChanged`) — the budget is
// that allocation in dp ([ServerWidget] converts px→dp), so the fit is
// correct at whatever size any launcher offers. Hosts that don't report
// an allocation (pre-API 31, or a host that doesn't push sizes) fall back
// to [WIDGET_HOST_BUDGET_DEFAULT_DP].
// ---------------------------------------------------------------------------

/** Hero row ("N/M UP") allocated height, dp — shared by the render and the budget checks below. */
const val WIDGET_HERO_HEIGHT_DP = 18

/** Secondary data-freshness line ("updated HH:mm") allocated height, dp. */
const val WIDGET_FRESHNESS_HEIGHT_DP = 10

/** One server-grid row allocated height, dp. */
const val WIDGET_GRID_ROW_HEIGHT_DP = 12

/** "+N more" overflow line allocated height, dp (same type size as the freshness line). */
const val WIDGET_OVERFLOW_HEIGHT_DP = 10

/** Widget root vertical padding, dp (4dp top + 4dp bottom). */
const val WIDGET_VERTICAL_PADDING_DP = 8

/**
 * Allocated height (dp) of a render with [gridRows] server-grid rows, the
 * optional secondary [freshness] line, and the optional [overflow] line —
 * the pinned arithmetic the budgets below are checked against:
 *
 *   18 (hero) + [10 (freshness)] + 12 × rows + [10 (overflow)] + 8 (padding)
 *
 * The worst case (3 rows, freshness, overflow) is 82 dp — the same figure
 * the 1×1 budgets above are pinned to against the 89 dp default budget.
 */
fun widgetLayoutHeightDp(gridRows: Int, freshness: Boolean, overflow: Boolean): Int =
    WIDGET_HERO_HEIGHT_DP +
        (if (freshness) WIDGET_FRESHNESS_HEIGHT_DP else 0) +
        gridRows * WIDGET_GRID_ROW_HEIGHT_DP +
        (if (overflow) WIDGET_OVERFLOW_HEIGHT_DP else 0) +
        WIDGET_VERTICAL_PADDING_DP

/**
 * The documented TIGHT short-row case, in dp — 64 dp. The Samsung S26
 * Ultra's One UI launcher row pitch is under 80 dp (its picker forced a
 * 2-row minimum against the old 80dp height floor), so once the 40dp
 * floor makes a 1-row placement possible there, the allocation of a 1-row
 * cell is a sub-80dp row — the fit is pinned conservatively against the
 * 64 dp tightest documented short row, the same way the 1×1 budgets are
 * pinned against the 89 dp default budget: every layout this module
 * renders must fit the pin with the arithmetic above — at the 64 dp pin
 * the rendered layout is at most 62 dp (3 grid rows, no freshness line)
 * or 60 dp (2 rows + the "\+N more" line, no freshness line), while the
 * full worst case (82 dp) and the 3-row compact (72 dp) exceed the pin —
 * which is exactly why the fit rules drop the secondary line first and
 * then grid rows at that budget. This constant is the PIN (the tests and
 * the metadata comment are checked against it); the live budget is the
 * host-reported allocation via [widgetBudgetDp] — a host that allocates
 * more than 64 dp (up to the 82 dp full-layout worst case) simply keeps
 * whatever the fit rules allow.
 */
const val WIDGET_HOST_BUDGET_SHORT_DP = 64

/**
 * Vertical budget (dp) for every host that does NOT report its allocation
 * (pre-API 31 hosts, or a host that never pushes `OPTION_APPWIDGET_SIZES`).
 * 89 dp is the tightest historically measured 1×1 cell (A52; the Pixel 7
 * Pro's AOSP launcher measures ≈116 dp), so the full-layout worst case
 * (82 dp) always fits and the full layout is always rendered — exactly
 * the pre-short-cell behavior on every such host, never a clip.
 */
const val WIDGET_HOST_BUDGET_DEFAULT_DP = 89

/**
 * The vertical budget (dp) for a render, from the height the host actually
 * allocated to the widget ([allocatedHeightDp] — the host-reported
 * allocation in dp, see [ServerWidget]'s budget read from
 * `OPTION_APPWIDGET_SIZES`). A missing or non-positive allocation (the
 * host didn't report one) falls back to [WIDGET_HOST_BUDGET_DEFAULT_DP].
 */
fun widgetBudgetDp(allocatedHeightDp: Int?): Int =
    allocatedHeightDp?.takeIf { it > 0 } ?: WIDGET_HOST_BUDGET_DEFAULT_DP

/**
 * The vertical layout decided per render against the host's vertical
 * budget — the height the host ACTUALLY ALLOCATED to the widget
 * ([widgetBudgetDp]: the `OPTION_APPWIDGET_SIZES` allocation on API 31+
 * reporting hosts, the [WIDGET_HOST_BUDGET_DEFAULT_DP] fallback
 * otherwise). The widget can occupy exactly one row, so the whole render
 * — hero + [showFreshness] line + grid rows + overflow line — must fit the
 * budget. Rules:
 *
 *  1. the FULL layout (the secondary freshness line kept) when it fits the
 *     budget — the historical render, unchanged on default-budget hosts;
 *  2. otherwise the COMPACT layout — the secondary freshness line is
 *     dropped; it is the secondary line (the hero verdict and the server
 *     grid carry the state), so dropping it loses the least; and
 *  3. if that still does not fit the budget, grid rows drop from the bottom
 *     until the remainder fits — the dropped servers join the "+N more"
 *     count, so no server is silently lost (the app shows the full list).
 *
 * [desiredGridRows] is the row count after the per-mode [widgetMaxCells]
 * budget (at most 3); [overflow] is whether the "+N more" line is present
 * at all (more servers than the budget shows). The returned layout is
 * guaranteed ≤ [budgetDp] whenever the budget holds at least the hero row
 * (26 dp — hero + padding, below any launcher row pitch ≥ 40 dp, the
 * AOSP-safe floor).
 */
data class WidgetVerticalLayout(
    /** Server-grid rows to render (≤ [desiredGridRows]). */
    val gridRows: Int,
    /** Whether the secondary data-freshness line is rendered. */
    val showFreshness: Boolean,
    /** The allocated total height of the render, dp (see [widgetLayoutHeightDp]). */
    val allocatedHeightDp: Int,
)

/** The decision for [widgetVerticalLayout] — see its KDoc for the rules. */
fun widgetVerticalLayout(desiredGridRows: Int, overflow: Boolean, budgetDp: Int): WidgetVerticalLayout {
    val full = widgetLayoutHeightDp(desiredGridRows, freshness = true, overflow = overflow)
    if (full <= budgetDp) return WidgetVerticalLayout(desiredGridRows, true, full)
    val compact = widgetLayoutHeightDp(desiredGridRows, freshness = false, overflow = overflow)
    if (compact <= budgetDp) return WidgetVerticalLayout(desiredGridRows, false, compact)
    var rows = desiredGridRows
    while (rows > 0 && widgetLayoutHeightDp(rows, freshness = false, overflow = overflow) > budgetDp) rows--
    return WidgetVerticalLayout(rows, false, widgetLayoutHeightDp(rows, freshness = false, overflow = overflow))
}
