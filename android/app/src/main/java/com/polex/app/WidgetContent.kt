package com.polex.app

import kotlin.math.ceil

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
// Full-name render (no ellipsis) — the grid cell shows the server name in
// FULL: the font scales down from the 10 sp base to the 6 sp floor so the
// name fits the cell's width budget, and a name that still overruns the
// budget at its font WRAPS to more lines, growing its row (the row prices
// the line boxes at the rendered size, and the short-cell budget below
// prices the sum). The wrap decision is MEASURED, not modeled: [NameMeasurer]
// reports the name's REAL wrapped line count at its RENDERED font size and
// the budget width — the production implementation (see [ServerWidget])
// lays out the real text (word-wrap, char-wrap for a token longer than the
// line) at sp→px, which carries the device density AND the system font
// scale — so a name the 0.6em font-fit model calls a single line (an
// enlarged font scale, a wider glyph run, word-boundary waste) measures its
// real lines, the font steps down until it fits the two-line wrapped row,
// and the row prices every measured line instead of clipping one; the JVM
// tests stub a deterministic word-pack measurer. The width budget is pinned
// to the tightest 1×1 cell width (A52 ≈89 dp — the same pin class as the
// vertical [WIDGET_HOST_BUDGET_DEFAULT_DP]): a span-agnostic fit, exactly
// like the vertical budget — the name that fits the 89 dp cell fits every
// wider placement (2×1, stretched cells). The font fit keeps the pinned
// pure arithmetic (testable on the JVM): average character advance 0.6 em
// (a visual choice — a font the model keeps at one line can still wrap, but
// the measured line count prices it instead of clipping), line box 1.5× the
// rendered font.
// ---------------------------------------------------------------------------

/** Pinned tightest 1×1 cell WIDTH, dp (A52 ≈89 dp — same pin class as the vertical default budget). */
const val WIDGET_CELL_1X1_WIDTH_DP = 89

/** Widget root horizontal padding, dp (6dp left + 6dp right). */
const val WIDGET_H_PADDING_DP = 12

/** Dot glyph ("●", 8 sp) allocated width, dp. */
const val WIDGET_CELL_DOT_WIDTH_DP = 8

/** Dot-to-name spacer, dp. */
const val WIDGET_CELL_SPACER_DP = 3

/** Baseline name font, sp (the historical 10 sp grid-cell type). */
const val WIDGET_NAME_BASE_FONT_SP = 10

/** Name font scale floor, sp — a name that doesn't fit at the floor wraps instead of shrinking further. */
const val WIDGET_NAME_MIN_FONT_SP = 6

/**
 * The width budget (dp) a server name has in one grid cell — pinned to
 * the tightest 1×1 cell ([WIDGET_CELL_1X1_WIDTH_DP]): content width
 * 89 - 12 (horizontal padding); 1 column takes the whole content width,
 * 2 columns a half (floor); minus the dot + spacer (11dp). 1 column:
 * 66dp; 2 columns: 27dp.
 */
fun widgetNameWidthBudgetDp(columns: Int): Int {
    val content = WIDGET_CELL_1X1_WIDTH_DP - WIDGET_H_PADDING_DP
    val half = if (normalizeWidgetColumns(columns) == WIDGET_COLUMNS_1) content else content / 2
    return half - WIDGET_CELL_DOT_WIDTH_DP - WIDGET_CELL_SPACER_DP
}

/**
 * The REAL wrapped line count of a name — the seam the wrap decision
 * ([widgetNameFit]) goes through. Production (see [ServerWidget]) lays
 * out the real text at its RENDERED size (sp→px, which carries the device
 * density AND the system font scale) wrapped at [budgetDp] (word-wrap,
 * char-wrap for a token longer than the line) — the exact layout the
 * render draws; the JVM tests stub a deterministic word-pack model. A
 * measured WIDTH cannot express the space word boundaries waste (the real
 * count can exceed `ceil(width / budget)`), so the count itself is
 * measured, not derived from the width.
 */
fun interface NameMeasurer {
    /** The lines [name] wraps to, rendered at [fontSp] sp inside [budgetDp] dp of width. */
    fun lineCount(name: String, fontSp: Int, budgetDp: Int): Int
}

/**
 * The maximum lines a name row targets at the rendered size — the font
 * fit steps down (toward [WIDGET_NAME_MIN_FONT_SP]) while the measured
 * line count exceeds it, so a name fits the two-line wrapped row instead
 * of clipping a third line. The single physically-necessary exception:
 * a name that still exceeds it AT THE FLOOR keeps its full measured line
 * count — the row prices every line ([widgetCellRowHeightDp]) and the
 * short-cell fit prices the space — never a clip or an ellipsis.
 */
const val WIDGET_NAME_MAX_LINES = 2

/** The measured fit of the FULL name in its cell (no ellipsis). */
data class WidgetNameFit(
    /** The name font (sp) — see [widgetNameFit]. */
    val fontSp: Int,
    /** The real wrapped line count at the rendered size — see [widgetNameFit]. */
    val lines: Int,
)

/**
 * The font and the measured lines of the FULL name in its cell —
 * measured, not modeled:
 *
 *  - the font starts at the model fit — the largest font at which the
 *    name fits the width budget in the pinned 0.6em model (average
 *    character advance 0.6em), clamped to
 *    [WIDGET_NAME_MIN_FONT_SP, [WIDGET_NAME_BASE_FONT_SP]]: short names
 *    stay at the 10 sp base (the historical render), long names scale
 *    toward the 6 sp floor, where they wrap instead of shrinking
 *    further;
 *  - it then steps down (1 sp at a time, never below the floor) while
 *    the MEASURED line count ([measure]) at the rendered size exceeds
 *    [WIDGET_NAME_MAX_LINES] — a name the model calls a single line (an
 *    enlarged system font scale, a wider glyph run, word-boundary wrap)
 *    shrinks to fit the two-line wrapped row at the size the render
 *    actually draws, instead of clipping a third line at `maxLines`;
 *  - only a name that still exceeds [WIDGET_NAME_MAX_LINES] lines AT THE
 *    FLOOR keeps its full measured count (the documented exception in
 *    [WIDGET_NAME_MAX_LINES]): the row prices every line and the
 *    short-cell fit prices the space, so the name renders in full —
 *    never clipped or ellipsized.
 */
fun widgetNameFit(name: String, columns: Int, measure: NameMeasurer): WidgetNameFit {
    if (name.isEmpty()) return WidgetNameFit(WIDGET_NAME_BASE_FONT_SP, 1)
    val budget = widgetNameWidthBudgetDp(columns)
    var font = (100 * budget / (60 * name.length))
        .coerceIn(WIDGET_NAME_MIN_FONT_SP, WIDGET_NAME_BASE_FONT_SP)
    while (font > WIDGET_NAME_MIN_FONT_SP && measure.lineCount(name, font, budget) > WIDGET_NAME_MAX_LINES) {
        font--
    }
    return WidgetNameFit(font, measure.lineCount(name, font, budget))
}

/**
 * The allocated row height (dp) for a cell needing [lines] name lines at
 * [fontSp] sp, priced at the RENDERED size — [fontScale] (the system font
 * scale, which sp→px carries) multiplies the font, so the price is
 * exactly what the render draws at every font scale:
 *
 *  - a single-line row scales the pinned 12dp
 *    ([WIDGET_GRID_ROW_HEIGHT_DP] — the shipped row height, ≈1.2× the
 *    10 sp base at scale 1.0, where the glyph span and its descenders
 *    fit with the row centered) by the font scale;
 *  - a wrapped row prices its line boxes at the pinned 1.5× factor
 *    (Glance type's ≈1.4-1.5× line box) so every line stays visible:
 *    `ceil(1.5 × lines × font × scale)`.
 *
 * At the default scale 1.0 the prices are the pinned figures (12dp
 * single-line, `ceil(1.5 × lines × font)` wrapped).
 */
fun widgetCellRowHeightDp(lines: Int, fontSp: Int, fontScale: Float): Int =
    if (lines <= 1) ceil(WIDGET_GRID_ROW_HEIGHT_DP * fontScale.toDouble()).toInt()
    else ceil(1.5 * lines * fontSp * fontScale.toDouble()).toInt()

/**
 * The pinned per-row heights (dp) of the grid rows for [items] in
 * [columns] mode — per row, the tallest cell (a two-column row is two
 * cells with possibly different fonts/wrap), priced at the rendered size
 * ([fontScale], see [widgetCellRowHeightDp]): the input to the short-cell
 * budget arithmetic, replacing the uniform 12dp rows for renders with
 * wrapped names (the wrap line count is [measure]'s real wrapped count
 * at the rendered size, see [widgetNameFit]).
 */
fun widgetRowHeightsDp(items: List<SrvStatus>, columns: Int, fontScale: Float, measure: NameMeasurer): List<Int> =
    widgetGridRows(items, columns).map { row ->
        row.maxOf { s ->
            val fit = widgetNameFit(s.name, columns, measure)
            widgetCellRowHeightDp(fit.lines, fit.fontSp, fontScale)
        }
    }


// ---------------------------------------------------------------------------
// Short-cell vertical fit (the 1-row placement every launcher can now make
// possible with the 40dp declared height floor — see polex_widget_info.xml).
//
// The budget is the tightest of the HOST-REPORTED CANDIDATE allocations:
// launchers on API 31+ push their candidate allocations into the stable
// widget options (`OPTION_APPWIDGET_SIZES`, a list of px sizes, delivered
// via `updateAppWidgetOptions` / `onAppWidgetOptionsChanged`) — the budget
// is the smallest of them in dp ([ServerWidget] converts px→dp and takes
// the minimum), so the span-agnostic fit is correct at whatever size any
// launcher offers. Hosts that don't report
// an allocation (pre-API 31, or a host that doesn't push sizes) fall back
// to [WIDGET_HOST_BUDGET_DEFAULT_DP] — as do implausible reads below the
// widget's declared 40dp height floor ([WIDGET_CELL_FLOOR_DP]): a launcher
// cannot allocate a widget below its declared minimum, so a sub-floor read
// is rejected rather than trusted (the S26 Ultra collapse: a sub-48dp
// allocation drove the pre-guard fit to the hero-only render — "N/M UP" +
// "+N more" with zero server rows).
// ---------------------------------------------------------------------------

/** Hero row ("N/M UP") allocated height, dp — shared by the render and the budget checks below. */
const val WIDGET_HERO_HEIGHT_DP = 18

/** Secondary data-freshness line ("updated HH:mm") allocated height, dp. */
const val WIDGET_FRESHNESS_HEIGHT_DP = 10

/** One server-grid row allocated height, dp, at the default font scale — [widgetCellRowHeightDp] scales the pin by the system font scale for single-line rows. */
const val WIDGET_GRID_ROW_HEIGHT_DP = 12

/** "+N more" overflow line allocated height, dp (same type size as the freshness line). */
const val WIDGET_OVERFLOW_HEIGHT_DP = 10

/** Widget root vertical padding, dp (4dp top + 4dp bottom). */
const val WIDGET_VERTICAL_PADDING_DP = 8

/**
 * The widget's DECLARED height floor, dp (`minHeight` / `minResizeHeight` in
 * `res/xml/polex_widget_info.xml`): a launcher cannot allocate a widget
 * below its declared minimum, so any reported allocation below this floor
 * is implausible — [widgetBudgetDp] rejects it (89 dp default budget).
 */
const val WIDGET_CELL_FLOOR_DP = 40

/**
 * Allocated height (dp) of a render with the given per-row grid heights,
 * the optional secondary [freshness] line, and the optional [overflow]
 * line — the pinned arithmetic the budgets below are checked against:
 *
 *   18 (hero) + [10 (freshness)] + Σ rowHeights + [10 (overflow)] + 8 (padding)
 *
 * A single-line name row is the pinned 12dp
 * ([WIDGET_GRID_ROW_HEIGHT_DP]); a wrapped name row is its line-box price
 * ([widgetCellRowHeightDp]) — both priced at the rendered size (the
 * system font scale included), so the figures below are the default-scale
 * (1.0) pins. The uniform 12dp special case is the
 * `12 × rows` form (the Int overload): the worst case (3 rows, freshness,
 * overflow) is 82 dp — the figure the 1×1 budgets are pinned to against
 * the 89 dp default budget — while a wrapped row prices more (the
 * six-server live-inventory worst case is 84 dp: 3 rows, two wrapped at
 * 18dp, freshness, no overflow).
 *
 * The row floor (see [widgetVerticalLayout]): at the 40 dp declared cell
 * floor the non-optional parts — 18 + one row + 8 — always fit, so one
 * server row renders at every legal allocation; the guaranteed "+N more"
 * line a row drop makes render prices the 48 dp uniform row-floor shape.
 */
fun widgetLayoutHeightDp(rowHeights: List<Int>, freshness: Boolean, overflow: Boolean): Int =
    WIDGET_HERO_HEIGHT_DP +
        (if (freshness) WIDGET_FRESHNESS_HEIGHT_DP else 0) +
        rowHeights.sum() +
        (if (overflow) WIDGET_OVERFLOW_HEIGHT_DP else 0) +
        WIDGET_VERTICAL_PADDING_DP

/** The uniform 12dp special case: `18 + [10] + 12 × gridRows + [10] + 8`. */
fun widgetLayoutHeightDp(gridRows: Int, freshness: Boolean, overflow: Boolean): Int =
    widgetLayoutHeightDp(List(gridRows) { WIDGET_GRID_ROW_HEIGHT_DP }, freshness, overflow)

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
 * then grid rows at that budget. Wrapped (multi-line) name rows price
 * more than the uniform 12 dp, and the fit rules handle that generically
 * over the per-row heights (the six-server live inventory's compact worst
 * case is 74 dp, so at the 64 dp pin it drops to the row floor). This
 * constant is the PIN (the tests and
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
 * The vertical budget (dp) for a render, from the tightest host-reported
 * candidate allocation ([allocatedHeightDp] — the smallest reported
 * allocation in dp, see [ServerWidget]'s budget read from
 * `OPTION_APPWIDGET_SIZES`). A missing allocation (the host didn't report
 * one) falls back to [WIDGET_HOST_BUDGET_DEFAULT_DP]; so does any
 * allocation BELOW the widget's declared 40 dp height floor
 * ([WIDGET_CELL_FLOOR_DP]) — the implausible-read guard: a launcher cannot
 * allocate a widget below its declared minimum, so a sub-floor read is
 * rejected rather than trusted. (A host that reports the 40 dp declared
 * minimum itself as its candidate allocation is at the floor, not below
 * it: the 40 dp read is legal and the fit rules' row floor —
 * [widgetVerticalLayout] — keeps at least one server row rendering at it.)
 */
fun widgetBudgetDp(allocatedHeightDp: Int?): Int =
    allocatedHeightDp?.takeIf { it >= WIDGET_CELL_FLOOR_DP } ?: WIDGET_HOST_BUDGET_DEFAULT_DP

/**
 * The vertical layout decided per render against the host's vertical
 * budget — the tightest of the host-reported candidate allocations
 * ([widgetBudgetDp]: the smallest `OPTION_APPWIDGET_SIZES` allocation on
 * API 31+ reporting hosts, the [WIDGET_HOST_BUDGET_DEFAULT_DP] fallback
 * otherwise). The render is span-agnostic (the widget cannot read its own
 * span from the stable options API), so the whole render — hero +
 * [showFreshness] line + grid rows + overflow line — must fit that
 * tightest candidate. Rules:
 *
 *  1. the FULL layout (the secondary freshness line kept) when it fits the
 *     budget — the historical render, unchanged on default-budget hosts;
 *  2. otherwise the COMPACT layout — the secondary freshness line is
 *     dropped; it is the secondary line (the hero verdict and the server
 *     grid carry the state), so dropping it loses the least; and
 *  3. if that still does not fit the budget, grid rows drop from the bottom
 *     until the remainder fits — the dropped servers join the "+N more"
 *     count, so no server is silently lost (the app shows the full list).
 *     The row drop NEVER passes the ROW FLOOR: at every legal budget (≥
 *     [WIDGET_CELL_FLOOR_DP] — [widgetBudgetDp] guards the read, so a live
 *     render always lands here) at least one server row renders; the
 *     "+N more" line may carry the remainder beyond what fits, never the
 *     entire list. The row-floor render prices the pinned arithmetic's
 *     non-optional parts — 18 + 12 + 8 = 38 dp (hero + one uniform row +
 *     padding), which always fits the 40 dp floor — plus the guaranteed
 *     "+N more" line a row drop makes render (48 dp), so a 40..47 dp
 *     allocation is exceeded by at most 8 dp (the line's height) — the
 *     pinned "one row at the 40 dp floor"; a wrapped first row prices its
 *     line boxes instead of the 12dp, so the row-floor render there is
 *     taller by the wrap price (only the bottom-anchored overflow line
 *     clips at that allocation, as with the uniform pin). The hero-only
 *     render (0 rows) remains only as the unreachable last resort BELOW
 *     the declared floor, where the pre-guard arithmetic is kept as-is.
 *
 * [rowHeights] is the pinned per-row heights after the per-mode
 * [widgetMaxCells] budget (at most 3 rows — [widgetRowHeightsDp]);
 * [overflow] is whether the "+N more" line is present at all (more
 * servers than the budget shows). The returned layout is ≤ [budgetDp]
 * whenever the budget holds at least the row-floor render (48 dp for
 * uniform rows); at a 40..47 dp allocation the row floor renders the
 * row-floor shape (≤8 dp past the allocation for uniform rows); below
 * the 40 dp declared floor (unreachable after [widgetBudgetDp]'s guard)
 * the hero-only render is the last resort, ≤ 36 dp.
 */
data class WidgetVerticalLayout(
    /** Server-grid rows to render (≤ the desired row count). */
    val gridRows: Int,
    /** Whether the secondary data-freshness line is rendered. */
    val showFreshness: Boolean,
    /** The allocated total height of the render, dp (see [widgetLayoutHeightDp]). */
    val allocatedHeightDp: Int,
)

/** The decision for [widgetVerticalLayout] — see its KDoc for the rules. */
fun widgetVerticalLayout(rowHeights: List<Int>, overflow: Boolean, budgetDp: Int): WidgetVerticalLayout {
    val full = widgetLayoutHeightDp(rowHeights, freshness = true, overflow = overflow)
    if (full <= budgetDp) return WidgetVerticalLayout(rowHeights.size, true, full)
    val compact = widgetLayoutHeightDp(rowHeights, freshness = false, overflow = overflow)
    if (compact <= budgetDp) return WidgetVerticalLayout(rowHeights.size, false, compact)
    // Row floor: at every legal budget (≥ the 40 dp declared cell floor,
    // [widgetBudgetDp] rejects sub-floor reads) at least one server row
    // renders — the "+N more" line carries the remainder, never the
    // entire list. Below the floor (unreachable on a live render) the
    // hero-only render remains the last resort, as before.
    val floorRows = if (rowHeights.isNotEmpty() && budgetDp >= WIDGET_CELL_FLOOR_DP) 1 else 0
    var rows = rowHeights.size
    while (rows > floorRows && widgetLayoutHeightDp(rowHeights.take(rows), freshness = false, overflow = true) > budgetDp) rows--
    return WidgetVerticalLayout(rows, false, widgetLayoutHeightDp(rowHeights.take(rows), freshness = false, overflow = true))
}

/**
 * The uniform-row form of [widgetVerticalLayout] — every grid row at the
 * pinned [WIDGET_GRID_ROW_HEIGHT_DP] (12dp), the pre-wrap pinned
 * arithmetic.
 */
fun widgetVerticalLayout(gridRows: Int, overflow: Boolean, budgetDp: Int): WidgetVerticalLayout =
    widgetVerticalLayout(List(gridRows) { WIDGET_GRID_ROW_HEIGHT_DP }, overflow, budgetDp)
