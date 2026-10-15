package com.polex.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Widget display settings — the render logic (WidgetContent.kt) the widget
 * applies at every offered placement (1×1/2×1 everywhere, plus 1×2/2×2 on
 * short-row launchers):
 *
 *  - column modes: 2 columns (default) = dot + name pairs per row, 1 column
 *    = one row per server, and the per-mode 1×1 vertical budget (6 cells
 *    vs 3 rows) plus the "+N more" overflow count;
 *  - short-cell fit: the per-render vertical layout against the host's
 *    vertical budget — the full layout when it fits, the compact layout
 *    (secondary freshness line dropped) when it doesn't, and grid rows
 *    dropped into the overflow count when even that doesn't fit the 64 dp
 *    documented tight case (the Samsung One UI short-row host) — with the
 *    ROW FLOOR: at every legal allocation (≥ the 40 dp declared cell
 *    floor) at least one server row renders, and the "+N more" line
 *    carries only the remainder beyond what fits, never the entire list
 *    (the S26 Ultra regression: a sub-48 dp allocation drove the pre-fix
 *    fit to the hero-only render — zero server rows + "+N more"); the
 *    budget itself is the host-reported allocation (OPTION_APPWIDGET_SIZES,
 *    API 31+) with the IMPLAUSIBLE-READ GUARD (a launcher cannot allocate
 *    below the widget's declared 40 dp floor, so a sub-floor read is
 *    rejected) and the 89 dp default fallback when the host reports
 *    none;
 *  - full-name render (no ellipsis): the cell's name font scales from the
 *    10 sp base to the 6 sp floor against the pinned cell width budget
 *    (89dp tightest 1×1: 66dp one column, 27dp two columns), and the wrap
 *    line count is the name's REAL wrapped count at the rendered font
 *    size — the tests inject a deterministic word-pack [NameMeasurer]
 *    stub (the production render lays out the real text with StaticLayout
 *    word-wrap at the budget width, sp→px carrying the system font scale,
 *    so a wider glyph run, an enlarged font scale, or word-boundary waste
 *    wraps into the priced lines instead of clipping — and the font steps
 *    down while the measured count exceeds the two-line wrapped row) —
 *    and the rows price their line boxes at the rendered size (font ×
 *    font scale) into the budget arithmetic (the six-server
 *    live-inventory worst case is 84 dp at the default scale and the 89 dp
 *    default budget);
 *  - server selection: `null` = all servers (the default), an explicit set
 *    = only those, in live-list order;
 *  - stale-selection handling: ids of removed servers are dropped silently
 *    — never rendered, never a crash (the widget filters the LIVE list).
 */
class WidgetContentTest {

    private fun srv(id: Int, online: Boolean = true, name: String = "s$id"): SrvStatus =
        SrvStatus(
            id = id,
            name = name,
            ip = "10.0.0.$id",
            mac = "",
            port = 22,
            check = "tcp",
            sshUser = "",
            sshKeyPath = "",
            sshPassword = "",
            poweroffCommand = "",
            online = online,
            latencyMs = 1L,
            lastChecked = "",
            lastWolAt = "",
            lastShutdownAt = "",
        )

    /**
     * The deterministic JVM stand-in for the production measurement: a
     * word-pack model — words pack greedily into lines of `floor(budget /
     * (advance × font × scale))` chars, and a token longer than the line
     * wraps character by character — the same wrap the render's TextView
     * performs (word-wrap, char-wrap). [advanceEm] is the average char
     * advance in hundredths of an em (60 = the pinned 0.6em model),
     * [scale] the system font scale baked into the rendered size.
     */
    private fun wordPackMeasure(advanceEm: Int, scale: Double = 1.0): NameMeasurer =
        NameMeasurer { name, font, budget ->
            val perLine = (budget / (advanceEm / 100.0 * font * scale)).toInt().coerceAtLeast(1)
            var lines = 1
            var used = 0
            for (word in name.split(' ')) {
                when {
                    word.isEmpty() -> Unit
                    word.length > perLine -> {
                        if (used > 0) lines++
                        lines += (word.length - 1) / perLine
                        used = word.length % perLine
                    }
                    used == 0 -> { used = word.length }
                    used + 1 + word.length <= perLine -> { used += 1 + word.length }
                    else -> { lines++; used = word.length }
                }
            }
            lines
        }

    /** The pinned 0.6em advance at the default font scale. */
    private val modelMeasure: NameMeasurer = wordPackMeasure(60)

    /** A wider-glyph render at the default scale: 0.63em advance (the wide-glyph case the 0.6em font-fit model underestimates). */
    private val wideGlyphMeasure: NameMeasurer = wordPackMeasure(63)

    /** A default-glyph render at a 1.3× system font scale (0.6 × 1.3 = 0.78em advance). */
    private val fontScale13Measure: NameMeasurer = wordPackMeasure(60, scale = 1.3)

    /**
     * The documented TIGHT short-row case, dp — the Samsung S26 Ultra's
     * One UI launcher row pitch (under 80 dp), pinned conservatively
     * against the 64 dp tightest documented short row. The production
     * budget is the host-reported allocation ([widgetBudgetDp]); this pin
     * is the worst case the fit rules are checked against in the tests
     * and the `polex_widget_info.xml` metadata comment.
     */
    private const val WIDGET_HOST_BUDGET_SHORT_DP = 64

    /** The uniform 12dp per-row heights ([WIDGET_GRID_ROW_HEIGHT_DP] per row). */
    private fun uniformRows(gridRows: Int): List<Int> = List(gridRows) { WIDGET_GRID_ROW_HEIGHT_DP }

    // --- column modes ---

    @Test
    fun normalizeClampsToTheSupportedModes() {
        assertEquals(WIDGET_COLUMNS_1, normalizeWidgetColumns(WIDGET_COLUMNS_1))
        assertEquals(WIDGET_COLUMNS_2, normalizeWidgetColumns(WIDGET_COLUMNS_2))
        // Anything else (a corrupted/corruptable stored value) falls back to
        // the two-column default rather than an unknown mode.
        assertEquals(WIDGET_COLUMNS_2, normalizeWidgetColumns(0))
        assertEquals(WIDGET_COLUMNS_2, normalizeWidgetColumns(3))
        assertEquals(WIDGET_COLUMNS_2, normalizeWidgetColumns(-1))
    }

    @Test
    fun defaultLayoutIsTwoColumns() {
        assertEquals(WIDGET_COLUMNS_2, WIDGET_COLUMNS_DEFAULT)
    }

    @Test
    fun budgetsFitThe1x1CellPerMode() {
        // 2 columns: 6 cells = 3 rows; 1 column: 3 rows.
        assertEquals(6, widgetMaxCells(WIDGET_COLUMNS_2))
        assertEquals(3, widgetMaxCells(WIDGET_COLUMNS_1))
        // Out-of-range modes budget the default (2-column) size.
        assertEquals(6, widgetMaxCells(7))
        // The per-mode budget's worst case — 18dp hero + 10dp freshness +
        // 12dp per row + 10dp "+N more" + 8dp padding, via the shared
        // widgetLayoutHeightDp arithmetic — must fit the 89dp default host
        // budget (the tightest historically measured 1×1 cell, A52), in
        // BOTH modes.
        for (columns in listOf(WIDGET_COLUMNS_1, WIDGET_COLUMNS_2)) {
            val budget = widgetMaxCells(columns)
            val rows = (budget + columns - 1) / columns
            val worstCaseDp = widgetLayoutHeightDp(uniformRows(rows), freshness = true, overflow = true)
            assertTrue(
                "$columns-column worst case $worstCaseDp dp exceeds the $WIDGET_HOST_BUDGET_DEFAULT_DP dp default budget",
                worstCaseDp <= WIDGET_HOST_BUDGET_DEFAULT_DP,
            )
        }
    }

    @Test
    fun twoColumnRowsAreDotNamePairs() {
        val live = (1..6).map { srv(it) }
        val rows = widgetGridRows(live, WIDGET_COLUMNS_2)
        assertEquals(3, rows.size)
        assertEquals(listOf(1, 2), rows[0].map { it.id })
        assertEquals(listOf(3, 4), rows[1].map { it.id })
        assertEquals(listOf(5, 6), rows[2].map { it.id })
        // An odd count within the budget keeps a trailing single cell.
        val rows5 = widgetGridRows(live.take(5), WIDGET_COLUMNS_2)
        assertEquals(1, rows5.last().size)
        assertEquals(5, rows5.last().first().id)
        // Past the budget the cap applies (6 cells), not the odd count.
        val rows7 = widgetGridRows((1..7).map { srv(it) }, WIDGET_COLUMNS_2)
        assertEquals(3, rows7.size)
        assertEquals(listOf(5, 6), rows7.last().map { it.id })
    }

    @Test
    fun oneColumnRowsAreOneServerPerRow() {
        val live = (1..3).map { srv(it) }
        val rows = widgetGridRows(live, WIDGET_COLUMNS_1)
        assertEquals(3, rows.size)
        assertEquals(live.map { it.id }, rows.map { row -> row.single().id })
    }

    @Test
    fun bothModesCapAtTheBudgetAndReportTheOverflow() {
        val live = (1..9).map { srv(it) }
        val two = widgetGridRows(live, WIDGET_COLUMNS_2)
        assertEquals(3, two.size) // 6 cells capped
        assertEquals(3, widgetOverflow(live, WIDGET_COLUMNS_2)) // "+3 more"

        val one = widgetGridRows(live, WIDGET_COLUMNS_1)
        assertEquals(3, one.size) // 3 rows capped
        assertEquals(6, widgetOverflow(live, WIDGET_COLUMNS_1)) // "+6 more"

        // Nothing past the budget → no overflow line.
        assertEquals(0, widgetOverflow(live.take(6), WIDGET_COLUMNS_2))
        assertEquals(0, widgetOverflow(live.take(3), WIDGET_COLUMNS_1))
    }

    // --- short-cell (compact) vertical layout ---

    @Test
    fun layoutHeightArithmeticIsPinnedPerComponent() {
        // 18 hero + [10 freshness] + 12/row + [10 overflow] + 8 padding
        // (uniform 12dp rows).
        assertEquals(82, widgetLayoutHeightDp(uniformRows(3), true, true))  // full worst case
        assertEquals(72, widgetLayoutHeightDp(uniformRows(3), false, true)) // compact worst case
        assertEquals(62, widgetLayoutHeightDp(uniformRows(3), false, false))
        assertEquals(50, widgetLayoutHeightDp(uniformRows(2), false, false))
        assertEquals(48, widgetLayoutHeightDp(uniformRows(1), true, false))
        assertEquals(58, widgetLayoutHeightDp(uniformRows(1), true, true))
        assertEquals(26, widgetLayoutHeightDp(uniformRows(0), false, false)) // hero + padding only
        // The per-row-height form prices wrapped rows the same way
        // (18 + [10] + Σrows + [10] + 8): the six-server live-inventory
        // shape (18/12/18 — two wrapped rows) is 84dp full / 74dp compact,
        // and the row-floor shape with a wrapped first row is 18+18+10+8.
        assertEquals(84, widgetLayoutHeightDp(listOf(18, 12, 18), true, false))
        assertEquals(74, widgetLayoutHeightDp(listOf(18, 12, 18), false, false))
        assertEquals(54, widgetLayoutHeightDp(listOf(18), false, true))
        // uniformRows is the uniform 12dp per-row form (12 × rows).
        assertEquals(
            widgetLayoutHeightDp(listOf(12, 12, 12), true, true),
            widgetLayoutHeightDp(uniformRows(3), true, true),
        )
    }

    // --- full-name render (no ellipsis) ---

    @Test
    fun nameWidthBudgetIsPinnedToTheTightestCell() {
        // 89dp tightest 1×1: content 77dp; 1 column takes it all (minus
        // the dot + spacer, 11dp), 2 columns a half (floor).
        assertEquals(66, widgetNameWidthBudgetDp(WIDGET_COLUMNS_1))
        assertEquals(27, widgetNameWidthBudgetDp(WIDGET_COLUMNS_2))
        // Out-of-range modes budget the two-column (narrower) side.
        assertEquals(27, widgetNameWidthBudgetDp(7))
    }

    @Test
    fun nameFontScalesWithinThePinnedRangeForTheLiveInventory() {
        // Live inventory (2026-10-15): i7-13700k(9), ai(2), dev200(6),
        // dev400(6), homelab420(10), prod480(7) — the longest names are
        // 10 chars.
        // 2 columns (27dp budget): short names keep the base, long names
        // scale toward the 6 sp floor.
        assertEquals(10, widgetNameFit("ai", WIDGET_COLUMNS_2, modelMeasure).fontSp)
        assertEquals(7, widgetNameFit("dev200", WIDGET_COLUMNS_2, modelMeasure).fontSp)
        assertEquals(7, widgetNameFit("dev400", WIDGET_COLUMNS_2, modelMeasure).fontSp)
        assertEquals(6, widgetNameFit("prod480", WIDGET_COLUMNS_2, modelMeasure).fontSp)
        assertEquals(6, widgetNameFit("i7-13700k", WIDGET_COLUMNS_2, modelMeasure).fontSp)
        assertEquals(6, widgetNameFit("homelab420", WIDGET_COLUMNS_2, modelMeasure).fontSp)
        // 1 column (66dp budget): the whole live inventory fits at the
        // 10 sp base on a single line.
        for (name in listOf("i7-13700k", "ai", "dev200", "dev400", "homelab420", "prod480")) {
            assertEquals("$name font", 10, widgetNameFit(name, WIDGET_COLUMNS_1, modelMeasure).fontSp)
            assertEquals("$name lines", 1, widgetNameFit(name, WIDGET_COLUMNS_1, modelMeasure).lines)
        }
        // The clamp: nothing below the 6 sp floor, nothing above the base.
        assertEquals(6, widgetNameFit("x".repeat(60), WIDGET_COLUMNS_2, modelMeasure).fontSp)
        assertEquals(10, widgetNameFit("x", WIDGET_COLUMNS_2, modelMeasure).fontSp)
        assertEquals(10, widgetNameFit("", WIDGET_COLUMNS_2, modelMeasure).fontSp)
        assertEquals(1, widgetNameFit("", WIDGET_COLUMNS_2, modelMeasure).lines)
    }

    @Test
    fun longestLiveNamesRenderInFullWithoutEllipsis() {
        // The longest live names (10 chars — i7-13700k, homelab420):
        // 1 column: one line at the 10 sp base (55dp ≤ 66dp budget).
        val oneCol = widgetNameFit("i7-13700k", WIDGET_COLUMNS_1, modelMeasure)
        assertEquals(10, oneCol.fontSp)
        assertEquals(1, oneCol.lines)
        // 2 columns: they overrun the 27dp budget at the 6 sp floor, so
        // they WRAP to two lines instead of ellipsizing — and the row
        // prices both lines (18dp at the default scale), so no line is
        // clipped.
        for (name in listOf("i7-13700k", "homelab420")) {
            val fit = widgetNameFit(name, WIDGET_COLUMNS_2, modelMeasure)
            assertEquals(6, fit.fontSp)
            assertEquals(2, fit.lines)
            assertEquals(18, widgetCellRowHeightDp(fit.lines, fit.fontSp, 1f))
        }
        // The pinned contract behind "no ellipsis": at the chosen font
        // the FULL name renders in its measured lines at the rendered
        // size for every live name, in both modes — the shorter names
        // stay one line in both budgets, the 9-10 char names wrap to two
        // lines only in the narrow 2-column budget.
        for (name in listOf("prod480", "dev200", "dev400", "ai")) {
            assertEquals(1, widgetNameFit(name, WIDGET_COLUMNS_2, modelMeasure).lines)
            assertEquals(10, widgetNameFit(name, WIDGET_COLUMNS_1, modelMeasure).fontSp)
            assertEquals(1, widgetNameFit(name, WIDGET_COLUMNS_1, modelMeasure).lines)
        }
    }

    @Test
    fun wrappedRowHeightsArePinned() {
        // Priced at the RENDERED size (font × system font scale): at the
        // default scale 1.0 the figures are the pinned ones — a single
        // line keeps the pinned 12dp row whatever the font (the shipped
        // row height), a wrapped row prices its line boxes at the pinned
        // 1.5× factor: ceil(1.5 × lines × font).
        assertEquals(12, widgetCellRowHeightDp(1, 10, 1f))
        assertEquals(12, widgetCellRowHeightDp(1, 6, 1f))
        assertEquals(18, widgetCellRowHeightDp(2, 6, 1f))
        assertEquals(21, widgetCellRowHeightDp(2, 7, 1f))
        assertEquals(24, widgetCellRowHeightDp(2, 8, 1f))
        assertEquals(30, widgetCellRowHeightDp(2, 10, 1f))
        assertEquals(27, widgetCellRowHeightDp(3, 6, 1f))
        // Scale 1.3 (an enlarged system font): the price is what the
        // render draws — the wrapped row's line boxes at the rendered
        // 7.8dp size (1.5 × 2 × 6 × 1.3 = 23.4 → 24dp, not the
        // scale-blind 18dp), the single-line pin scaled by the font
        // scale (12 × 1.3 = 15.6 → 16dp — the ≈1.2× row factor stays
        // ≈1.2× the rendered 13sp font, so the glyph span and its
        // descenders stay inside the row), and the floor-exception
        // three-line row (1.5 × 3 × 6 × 1.3 = 35.1 → 36dp).
        assertEquals(24, widgetCellRowHeightDp(2, 6, 1.3f))
        assertEquals(16, widgetCellRowHeightDp(1, 10, 1.3f))
        assertEquals(16, widgetCellRowHeightDp(1, 6, 1.3f))
        assertEquals(36, widgetCellRowHeightDp(3, 6, 1.3f))
    }

    @Test
    fun pathologicalNamesWrapInsteadOfEllipsizing() {
        // Far past the live inventory: a 40-char name prices 3 lines at
        // the 6 sp floor in 1 column (18 chars per line: 66dp / 3.6dp —
        // 18/18/4), row 27dp; a 16-char name prices 3 lines at the floor
        // in 2 columns (7 chars per line: 7/7/2), row 27dp. The budget
        // arithmetic prices those rows like any other — the name never
        // clips or ellipsizes and the row layout never breaks.
        val long = widgetNameFit("x".repeat(40), WIDGET_COLUMNS_1, modelMeasure)
        assertEquals(6, long.fontSp)
        assertEquals(3, long.lines)
        assertEquals(27, widgetCellRowHeightDp(long.lines, long.fontSp, 1f))
        val mid = widgetNameFit("x".repeat(16), WIDGET_COLUMNS_2, modelMeasure)
        assertEquals(6, mid.fontSp)
        assertEquals(3, mid.lines)
        assertEquals(27, widgetCellRowHeightDp(mid.lines, mid.fontSp, 1f))
        // End to end: a single 27dp wrapped row keeps the FULL layout at
        // the 64dp tight case (63dp ≤ 64dp) and holds the row floor at
        // the 40dp floor (one row renders, priced at 63dp).
        val wrapped = listOf(27)
        val short = widgetVerticalLayout(wrapped, false, WIDGET_HOST_BUDGET_SHORT_DP)
        assertEquals(1, short.gridRows)
        assertTrue(short.showFreshness)
        assertEquals(63, short.allocatedHeightDp)
        val floor = widgetVerticalLayout(wrapped, false, WIDGET_CELL_FLOOR_DP)
        assertEquals(1, floor.gridRows)
        assertFalse(floor.showFreshness)
        assertEquals(63, floor.allocatedHeightDp)
    }

    @Test
    fun wideGlyphNameAtDefaultScaleWrapsInsteadOfClipping() {
        // The wide-glyph case the 0.6em font-fit model underestimates: a
        // 12-char name averaging 0.63em/char, 1 column. The model font
        // fit lands at 9 sp (model width 65dp ≤ the 66dp budget), but the
        // MEASURED wrap at the rendered 9 sp is two lines (11 chars per
        // line: 66dp / 5.67dp — 11/1) — the name WRAPS to the two-line
        // row (27dp) instead of clipping at maxLines=1 — the
        // pre-measurement failure mode.
        val name = "m".repeat(12)
        val fit = widgetNameFit(name, WIDGET_COLUMNS_1, wideGlyphMeasure)
        assertEquals(9, fit.fontSp)
        assertEquals(2, fit.lines)
        assertEquals(27, widgetCellRowHeightDp(fit.lines, fit.fontSp, 1f))
    }

    @Test
    fun nameAtEnlargedFontScaleWrapsInsteadOfClipping() {
        // The font-scale case the 0.6em font-fit model underestimates:
        // "i7-13700k" (9 chars) at a 1.3× system font scale, 1 column.
        // The model font fit keeps the 10 sp base (model width 54dp ≤ the
        // 66dp budget), but the MEASURED wrap at the rendered 13sp size
        // (sp→px carries the 1.3 scale — 8 chars per line: 66dp / 7.8dp —
        // 8/1) overruns the 66dp budget: the name WRAPS to the two-line
        // row, priced at the rendered size (1.5 × 2 × 10 × 1.3 = 39.0 →
        // 39dp) instead of clipping at maxLines=1 — the
        // pre-measurement failure mode.
        val fit = widgetNameFit("i7-13700k", WIDGET_COLUMNS_1, fontScale13Measure)
        assertEquals(10, fit.fontSp)
        assertEquals(2, fit.lines)
        assertEquals(39, widgetCellRowHeightDp(fit.lines, fit.fontSp, 1.3f))
    }

    @Test
    fun threeWordNameAtEnlargedScaleFallsBackToTheFloorLineCount() {
        // The word-boundary case the old ceil(width / budget) price
        // under-priced: "rack one nas" (12 chars, 3 words) at the 1.3×
        // font scale, 2 columns. The model fit clamps to the 6 sp floor,
        // and the MEASURED word wrap at the rendered 7.8dp size — 5 chars
        // per line (27dp / 4.68dp) — is 3 lines: 'rack' / 'one' / 'nas'
        // (a word pair + the space overruns the 27dp budget, so every
        // word takes its own line — the width model priced ceil(47.3dp /
        // 27dp) = 2, a clipped third line). At the floor the name keeps
        // its full measured line count — the single
        // physically-necessary exception — and the row prices all three
        // lines at the rendered size (1.5 × 3 × 6 × 1.3 = 35.1 → 36dp),
        // so the third word renders instead of clipping at maxLines=2.
        val fit = widgetNameFit("rack one nas", WIDGET_COLUMNS_2, fontScale13Measure)
        assertEquals(6, fit.fontSp)
        assertEquals(3, fit.lines)
        assertEquals(36, widgetCellRowHeightDp(fit.lines, fit.fontSp, 1.3f))
    }

    @Test
    fun threeWideWordsAtDefaultScaleWrapToThreeLines() {
        // The default-scale sibling — no font-scale change needed:
        // 'wwww wwww wwww' (14 chars) in 2 columns. The old price was 2
        // lines (ceil(43.2dp / 27dp)), but the real word wrap at the 6 sp
        // floor is 3 lines: a word pair + the space (9 chars = 32.4dp)
        // overruns the 27dp budget (7 chars per line), so each word takes
        // its own line. The row prices all three (27dp) — the third word
        // renders instead of clipping at maxLines=2.
        val fit = widgetNameFit("wwww wwww wwww", WIDGET_COLUMNS_2, modelMeasure)
        assertEquals(6, fit.fontSp)
        assertEquals(3, fit.lines)
        assertEquals(27, widgetCellRowHeightDp(fit.lines, fit.fontSp, 1f))
    }

    @Test
    fun fontFitStepsDownWhileTheMeasuredCountExceedsTwoLines() {
        // The clamp step: the model fit keeps the 10 sp base, but the
        // measured wrap is 3 lines until the font steps down to 7 sp (2
        // lines there) — the fit must price the name at 7 sp, not the
        // model's 10 sp (whose third line would clip at maxLines=2).
        val step = NameMeasurer { _, font, _ -> if (font > 7) 3 else 2 }
        val fit = widgetNameFit("x", WIDGET_COLUMNS_2, step)
        assertEquals(7, fit.fontSp)
        assertEquals(2, fit.lines)
        // And it never steps below the 6 sp floor: a name that measures
        // 3 lines at every font keeps the floor font and its full
        // measured count (the documented floor fallback).
        val never = NameMeasurer { _, _, _ -> 3 }
        val floor = widgetNameFit("rack one nas", WIDGET_COLUMNS_2, never)
        assertEquals(6, floor.fontSp)
        assertEquals(3, floor.lines)
    }

    @Test
    fun sixServerLiveInventoryGeometry() {
        // The live six (2026-10-15): i7-13700k, ai, dev200, dev400,
        // homelab420, prod480. 2 columns: rows (i7-13700k|ai),
        // (dev200|dev400), (homelab420|prod480) → heights 18/12/18:
        // the 9-10 char names wrap at the 6 sp floor (2 lines → 18dp
        // rows); prod480 fits one line at 6 sp (26dp ≤ 27dp).
        val live = listOf(
            srv(1, name = "i7-13700k"), srv(2, name = "ai"), srv(3, name = "dev200"),
            srv(5, name = "dev400"), srv(10, name = "homelab420"), srv(11, name = "prod480"),
        )
        val columns = WIDGET_COLUMNS_2
        val rows = widgetGridRows(live, columns)
        assertEquals(3, rows.size)
        val rowHeights = widgetRowHeightsDp(rows, columns, 1f, modelMeasure)
        assertEquals(listOf(18, 12, 18), rowHeights)
        assertEquals(0, widgetOverflow(live, columns)) // all six fit the 6-cell budget
        // 89dp-class 1×1: the full layout (84dp — the two wrapped rows
        // price 18dp each instead of 12dp) fits: all six listed, no
        // overflow line, freshness kept.
        val layout = widgetVerticalLayout(rowHeights, widgetOverflow(live, columns) > 0, WIDGET_HOST_BUDGET_DEFAULT_DP)
        assertEquals(3, layout.gridRows)
        assertTrue(layout.showFreshness)
        assertEquals(84, layout.allocatedHeightDp)
        assertEquals(0, live.size - rows.take(layout.gridRows).sumOf { it.size })
        // Every stretched (2-row) allocation (80..158dp): all six still
        // list as three rows with no overflow line — the 80dp edge keeps
        // the 74dp compact (freshness drops, the rows stay).
        for (pitch in 40..79) {
            val budget = 2 * pitch
            val l = widgetVerticalLayout(rowHeights, false, budget)
            assertEquals("$budget dp: all six list as 3 rows", 3, l.gridRows)
            assertEquals(0, live.size - rows.take(l.gridRows).sumOf { it.size })
        }
        // 1×1 at the 40dp floor: the row floor holds — one row renders
        // (the wrapped 18dp first row), "+4 more" carries the remainder.
        val floor = widgetVerticalLayout(rowHeights, false, WIDGET_CELL_FLOOR_DP)
        assertEquals(1, floor.gridRows)
        assertEquals(4, live.size - rows.take(floor.gridRows).sumOf { it.size })
        // 1 column, 1×1 (89dp-class): the live names all fit one line at
        // the 10 sp base (12dp rows) — three listed + a "+3 more".
        val oneColHeights = widgetRowHeightsDp(widgetGridRows(live, WIDGET_COLUMNS_1), WIDGET_COLUMNS_1, 1f, modelMeasure)
        assertEquals(listOf(12, 12, 12), oneColHeights)
        assertEquals(3, widgetOverflow(live, WIDGET_COLUMNS_1))
        val oneCol = widgetVerticalLayout(oneColHeights, true, WIDGET_HOST_BUDGET_DEFAULT_DP)
        assertEquals(3, oneCol.gridRows)
        assertTrue(oneCol.showFreshness)
        assertEquals(widgetLayoutHeightDp(uniformRows(3), true, true), oneCol.allocatedHeightDp) // 82dp
        // The same inventory at the 1.3× font scale: the rendered size
        // carries the scale (7.8dp for the 6 sp floor — 5 chars per line
        // at the 27dp budget; 5.46dp for the 7 sp dev200/dev400 — 4), so
        // the 9-10 char names wrap to two lines at 6 sp (24dp rows —
        // 1.5 × 2 × 6 × 1.3 = 23.4 → 24, not the scale-blind 18dp) and
        // the 6-char dev200/dev400 wrap at their 7 sp model fit (28dp —
        // 1.5 × 2 × 7 × 1.3 = 27.3 → 28): the 89dp default host drops
        // one row into the "+N more" count (88dp = 18 + 24 + 28 + 10 + 8)
        // instead of clipping the line boxes.
        val scaleHeights = widgetRowHeightsDp(rows, columns, 1.3f, fontScale13Measure)
        assertEquals(listOf(24, 28, 24), scaleHeights)
        val scaleLayout = widgetVerticalLayout(scaleHeights, widgetOverflow(live, columns) > 0, WIDGET_HOST_BUDGET_DEFAULT_DP)
        assertEquals(2, scaleLayout.gridRows)
        assertFalse(scaleLayout.showFreshness)
        assertEquals(88, scaleLayout.allocatedHeightDp)
        assertEquals(2, live.size - rows.take(scaleLayout.gridRows).sumOf { it.size })
    }

    @Test
    fun defaultBudgetAlwaysKeepsTheFullLayout() {
        // 89 dp default (the tightest historically measured cell): the full
        // worst case (82 dp) fits every shape, so the historical render —
        // freshness line kept, no dropped rows — is unchanged on the Pixel
        // 7 Pro (≈116 dp cells), the A52 and every unknown host.
        for (rows in 0..3) {
            for (overflow in listOf(false, true)) {
                val layout = widgetVerticalLayout(uniformRows(rows), overflow, WIDGET_HOST_BUDGET_DEFAULT_DP)
                assertEquals(rows, layout.gridRows)
                assertTrue(layout.showFreshness)
                assertEquals(widgetLayoutHeightDp(uniformRows(rows), true, overflow), layout.allocatedHeightDp)
            }
        }
    }

    @Test
    fun shortBudgetKeepsTheFreshnessLineOnlyWhenTheFullLayoutFits() {
        // 64 dp tight case: 1 row full (48 dp, 58 with overflow) fits —
        // small widgets keep the freshness line on the short host too.
        val one = widgetVerticalLayout(uniformRows(1), false, WIDGET_HOST_BUDGET_SHORT_DP)
        assertTrue(one.showFreshness)
        assertEquals(1, one.gridRows)
        assertEquals(48, one.allocatedHeightDp)
        val oneMore = widgetVerticalLayout(uniformRows(1), true, WIDGET_HOST_BUDGET_SHORT_DP)
        assertTrue(oneMore.showFreshness)
        assertEquals(58, oneMore.allocatedHeightDp)
        // 2 rows full = 60 dp ≤ 64 → the full layout (freshness kept) is
        // kept even at the 64 dp tight row.
        val two = widgetVerticalLayout(uniformRows(2), false, WIDGET_HOST_BUDGET_SHORT_DP)
        assertTrue(two.showFreshness)
        assertEquals(2, two.gridRows)
        assertEquals(60, two.allocatedHeightDp)
        // 2 rows + overflow full = 70 dp > 64 → compact: 60 dp (the
        // secondary line drops, the rows stay).
        val twoMore = widgetVerticalLayout(uniformRows(2), true, WIDGET_HOST_BUDGET_SHORT_DP)
        assertFalse(twoMore.showFreshness)
        assertEquals(2, twoMore.gridRows)
        assertEquals(60, twoMore.allocatedHeightDp)
    }

    @Test
    fun shortBudgetDropsGridRowsIntoTheOverflowCount() {
        // 3 rows + overflow: full 82 dp > 64 and compact 72 dp > 64 → 2
        // rows, no freshness, 60 dp. The dropped servers join the "+N
        // more" count at render time (hidden = items - shown cells), so no
        // server is silently lost.
        val layout = widgetVerticalLayout(uniformRows(3), true, WIDGET_HOST_BUDGET_SHORT_DP)
        assertEquals(2, layout.gridRows)
        assertFalse(layout.showFreshness)
        assertEquals(60, layout.allocatedHeightDp)
        // 3 rows WITHOUT overflow: compact 62 dp fits → the rows are kept
        // and only the secondary freshness line drops.
        val noOverflow = widgetVerticalLayout(uniformRows(3), false, WIDGET_HOST_BUDGET_SHORT_DP)
        assertEquals(3, noOverflow.gridRows)
        assertFalse(noOverflow.showFreshness)
        assertEquals(62, noOverflow.allocatedHeightDp)
    }

    @Test
    fun rowDropPricesTheGuaranteedOverflowLine() {
        // Dropping rows makes the render show the "+N more" line from the
        // POST-fit count even when the pre-fit overflow flag was false (6
        // servers, 2 columns: 3 rows fill the 6-cell budget → no pre-fit
        // overflow) — the decision must price that line.
        val layout = widgetVerticalLayout(uniformRows(3), false, 50)
        assertEquals(1, layout.gridRows)
        assertFalse(layout.showFreshness)
        // The guaranteed overflow line is priced into the returned height:
        // 48 dp = hero + 1 row + overflow line + padding, not the 50 dp
        // unpriced shape (whose render would add a 10dp line the decision
        // never priced, clipping the allocation).
        assertEquals(widgetLayoutHeightDp(uniformRows(1), false, true), layout.allocatedHeightDp)
        // Same invariant at a 56 dp allocation (short-row host):
        // 1 row + overflow line = 48 dp ≤ 56.
        val taller = widgetVerticalLayout(uniformRows(3), false, 56)
        assertEquals(1, taller.gridRows)
        assertFalse(taller.showFreshness)
        assertEquals(48, taller.allocatedHeightDp)
    }

    @Test
    fun shortBudgetLayoutNeverExceedsTheBudget() {
        // Every shape at the 64 dp tight case fits the budget.
        for (rows in 0..3) {
            for (overflow in listOf(false, true)) {
                val layout = widgetVerticalLayout(uniformRows(rows), overflow, WIDGET_HOST_BUDGET_SHORT_DP)
                assertTrue(
                    "$rows rows/$overflow overflow exceeds the budget",
                    layout.allocatedHeightDp <= WIDGET_HOST_BUDGET_SHORT_DP,
                )
            }
        }
        // A degenerate 36 dp budget (below any real row pitch — the AOSP-
        // safe 40dp floor) degrades to the hero-only render, never above
        // the budget.
        val tiny = widgetVerticalLayout(uniformRows(3), true, 36)
        assertEquals(0, tiny.gridRows)
        assertEquals(36, tiny.allocatedHeightDp)
    }

    @Test
    fun budgetIsTheHostReportedAllocationWithTheDefaultFallback() {
        // A reported allocation (the host's allocated height in dp) is the
        // budget, whatever it is — the fit rules then decide the layout.
        assertEquals(84, widgetBudgetDp(84))
        assertEquals(64, widgetBudgetDp(64))
        assertEquals(116, widgetBudgetDp(116))
        // No allocation (the host didn't report one — pre-API 31, or a
        // host that never pushes OPTION_APPWIDGET_SIZES) → the 89dp
        // default, which always keeps the full layout (82dp worst case).
        assertEquals(WIDGET_HOST_BUDGET_DEFAULT_DP, widgetBudgetDp(null))
    }

    @Test
    fun subFloorAllocationsAreImplausibleAndFallBackToTheDefault() {
        // The launcher cannot allocate a widget below its declared 40 dp
        // height floor (minHeight/minResizeHeight in polex_widget_info.xml),
        // so any read below the floor is implausible — a corrupted or
        // misinterpreted report — and gets the 89 dp default budget
        // instead of a degenerate fit. The degenerate non-positive reads
        // are sub-floor too and fall back the same way.
        assertEquals(WIDGET_HOST_BUDGET_DEFAULT_DP, widgetBudgetDp(WIDGET_CELL_FLOOR_DP - 1))
        assertEquals(WIDGET_HOST_BUDGET_DEFAULT_DP, widgetBudgetDp(34))
        assertEquals(WIDGET_HOST_BUDGET_DEFAULT_DP, widgetBudgetDp(0))
        assertEquals(WIDGET_HOST_BUDGET_DEFAULT_DP, widgetBudgetDp(-12))
        // The floor itself is LEGAL — a host that reports the widget's
        // declared minimum as its candidate allocation is at the floor,
        // not below it: the read is trusted, and the fit rules' row floor
        // keeps at least one server row rendering at it.
        assertEquals(WIDGET_CELL_FLOOR_DP, widgetBudgetDp(WIDGET_CELL_FLOOR_DP))
        // Every legal allocation at/above the floor passes through as the
        // budget, unchanged.
        for (dp in WIDGET_CELL_FLOOR_DP..160) assertEquals("$dp dp", dp, widgetBudgetDp(dp))
    }

    @Test
    fun s26CollapseAllocationPinsTheRegression() {
        // The S26 Ultra report (the PR #15 build): the placed 1-row widget
        // rendered only the hero row ("6/6 UP") + "+6 more" — ZERO server
        // rows, with six servers live. Its One UI launcher reports a
        // sub-48 dp allocation for the 1-row placement — the minimal one
        // is the 40 dp declared floor itself (a launcher can't allocate
        // below the widget's declared minimum, so the smallest report it
        // can push is 40 dp): the pre-fix fit at ANY allocation below the
        // 48 dp row-floor render dropped all three grid rows to the 36 dp
        // hero-only shape, and the render showed the entire list as
        // "+6 more".
        val servers = (1..6).map { srv(it) }
        val rows = widgetGridRows(servers, WIDGET_COLUMNS_2)
        val hidden = widgetOverflow(servers, WIDGET_COLUMNS_2)
        assertEquals(3, rows.size)
        assertEquals(0, hidden)
        // At the minimal collapsing allocation (40 dp — legal, at the
        // declared floor, so the guard keeps it): the row floor holds —
        // one row of two servers renders, and "+4 more" carries only the
        // remainder beyond what fits, never the entire list.
        val layout = widgetVerticalLayout(uniformRows(rows.size), hidden > 0, widgetBudgetDp(40))
        assertEquals(1, layout.gridRows)
        assertFalse(layout.showFreshness)
        // The pinned row-floor shape: 18 + 12 + 10 + 8 = 48 dp (hero + one
        // row + the guaranteed overflow line + padding; the 38 dp
        // non-optional parts fit the 40 dp floor, the line prices 48 dp).
        assertEquals(widgetLayoutHeightDp(uniformRows(1), false, true), layout.allocatedHeightDp)
        val shown = rows.take(layout.gridRows).sumOf { it.size }
        assertEquals(2, shown)
        assertEquals(4, servers.size - shown) // "+4 more", not "+6 more"
        // Every allocation the S26 plausibly reports — 40..47 dp, all
        // below the 48 dp row-floor render — keeps the row floor: one row
        // never zero. (A sub-floor read, e.g. 39 dp, is implausible: the
        // guard rejects it to the 89 dp default, where the full layout —
        // all three rows — renders.)
        for (budget in 40..47) {
            val l = widgetVerticalLayout(uniformRows(rows.size), hidden > 0, widgetBudgetDp(budget))
            assertEquals("$budget dp must keep one row", 1, l.gridRows)
            assertTrue("$budget dp: the overflow carries the remainder", servers.size - rows.take(l.gridRows).sumOf { it.size } < servers.size)
        }
        assertEquals(WIDGET_HOST_BUDGET_DEFAULT_DP, widgetBudgetDp(39))
        assertEquals(3, widgetVerticalLayout(uniformRows(rows.size), hidden > 0, widgetBudgetDp(39)).gridRows)
    }

    @Test
    fun rowFloorHoldsAtEveryLegalAllocation() {
        // The offer matrix can produce exactly these allocations: a 1-row
        // cell of 40..80dp (the declared height range) or a 2-row cell of
        // 2×pitch on a short-row launcher (pitch 40..79dp → 80..158dp).
        // At EVERY allocation from the 40dp declared floor up, a non-empty
        // grid renders at least one server row; and at every allocation ≥
        // the 48dp row-floor shape the render fits the allocation.
        val budgets = (40..80).toSet() + (40..79).map { 2 * it }.toSet()
        for (budget in budgets) {
            for (rows in 1..3) {
                for (overflow in listOf(false, true)) {
                    val layout = widgetVerticalLayout(uniformRows(rows), overflow, budget)
                    assertTrue(
                        "$budget dp / $rows rows / overflow=$overflow: the row floor renders ≥1 server row",
                        layout.gridRows >= 1,
                    )
                    if (budget >= 48) {
                        assertTrue(
                            "$budget dp: the ${layout.allocatedHeightDp}dp render exceeds the allocation",
                            layout.allocatedHeightDp <= budget,
                        )
                    } else {
                        // 40..47dp — the pinned row floor: hero + one row +
                        // padding (38 dp, the arithmetic's non-optional
                        // parts) always fits; a row drop prices the
                        // guaranteed overflow line into the 48 dp shape,
                        // at most 8 dp past the allocation (the line's
                        // height). The one shape that needs no drop — 1 row
                        // without overflow — stays at the 38 dp compact.
                        assertEquals(1, layout.gridRows)
                        val expected = if (rows == 1 && !overflow)
                            widgetLayoutHeightDp(uniformRows(1), false, false)
                        else
                            widgetLayoutHeightDp(uniformRows(1), false, true)
                        assertEquals("$budget dp", expected, layout.allocatedHeightDp)
                    }
                }
            }
        }
        // The empty grid (no servers — the "no data" render) keeps fitting
        // every allocation.
        for (budget in 40..80) {
            for (overflow in listOf(false, true)) {
                val layout = widgetVerticalLayout(uniformRows(0), overflow, budget)
                assertEquals(0, layout.gridRows)
                assertTrue("$budget dp: empty render exceeds the allocation", layout.allocatedHeightDp <= budget)
            }
        }
    }

    @Test
    fun sixServersTwoColumnsFitThreeRowsWithNoOverflow() {
        // Six servers, the default 2-column mode: 3 rows of two cells —
        // all six listed, the overflow count zero, so NO "+N more" line —
        // in the 89dp-class 1×1 cell and in every stretched (2-row) cell.
        val servers = (1..6).map { srv(it) }
        val rows = widgetGridRows(servers, WIDGET_COLUMNS_2)
        assertEquals(3, rows.size)
        assertEquals(6, rows.sumOf { it.size })
        assertEquals(0, widgetOverflow(servers, WIDGET_COLUMNS_2))
        // 89 dp-class 1×1 (A52; the Pixel measures ≈116 dp — the same
        // default-budget class): the full layout — 72 dp, freshness line
        // kept — holds, and all three rows render.
        for (budget in intArrayOf(89, 116)) {
            val layout = widgetVerticalLayout(uniformRows(rows.size), widgetOverflow(servers, WIDGET_COLUMNS_2) > 0, budget)
            assertEquals("$budget dp: all six must fit as 3 rows", 3, layout.gridRows)
            assertTrue("$budget dp: the full layout keeps the freshness line", layout.showFreshness)
            assertEquals(widgetLayoutHeightDp(uniformRows(3), true, false), layout.allocatedHeightDp)
            // No servers hidden → no "+N more" line in the render.
            assertEquals(0, servers.size - rows.take(layout.gridRows).sumOf { it.size })
        }
        // Every stretched (2-row) allocation — 2×pitch, pitch 40..79dp →
        // 80..158dp, short-row launchers: all six still fit as 3 rows
        // with no overflow line (the 80-81dp edge drops only the
        // secondary freshness line: the 62 dp compact worst case).
        for (pitch in 40..79) {
            val budget = 2 * pitch
            val layout = widgetVerticalLayout(uniformRows(rows.size), widgetOverflow(servers, WIDGET_COLUMNS_2) > 0, budget)
            assertEquals("stretched ${budget}dp: all six must fit as 3 rows", 3, layout.gridRows)
            assertEquals(
                "stretched ${budget}dp: no servers hidden, no overflow line",
                0, servers.size - rows.take(layout.gridRows).sumOf { it.size },
            )
            assertTrue("stretched ${budget}dp: the render fits", layout.allocatedHeightDp <= budget)
            if (budget >= 82) assertTrue("stretched ${budget}dp: full layout kept", layout.showFreshness)
        }
    }

    @Test
    fun sixServersOneColumnAt1x1ListsThreePlusThreeMore() {
        // Six servers, 1-column mode, 1×1 (89dp-class): the per-mode budget
        // is 3 rows (one server each) → three servers listed + a "+3
        // more" remainder — the pinned full-worst-case 82 dp render fits
        // the 89 dp budget with the freshness line kept.
        val servers = (1..6).map { srv(it) }
        val rows = widgetGridRows(servers, WIDGET_COLUMNS_1)
        assertEquals(3, rows.size)
        assertEquals(listOf(1, 2, 3), rows.map { it.single().id })
        assertEquals(3, widgetOverflow(servers, WIDGET_COLUMNS_1)) // "+3 more"
        val layout = widgetVerticalLayout(uniformRows(rows.size), widgetOverflow(servers, WIDGET_COLUMNS_1) > 0, WIDGET_HOST_BUDGET_DEFAULT_DP)
        assertEquals(3, layout.gridRows)
        assertTrue(layout.showFreshness)
        assertEquals(widgetLayoutHeightDp(uniformRows(3), true, true), layout.allocatedHeightDp)
        // The remainder beyond what fits — exactly three, the rest of the
        // list — never the entire list.
        assertEquals(3, servers.size - rows.take(layout.gridRows).sumOf { it.size })
    }

    @Test
    fun twoRowCellAllocationsKeepTheFullLayout() {
        // A 2-row offer (short-row launchers only) allocates 2×pitch ≥
        // 2×40dp = 80dp (the 40dp minResize floor). At ≥82dp the full
        // worst case (82dp) fits, so the full layout — freshness line
        // kept, no dropped rows — is rendered at every 2-row allocation;
        // at the 80-81dp edge only the shapes that don't fit drop to the
        // 72dp compact worst case. It always fits.
        for (pitch in 40..79) {
            val budget = 2 * pitch
            for (rows in 0..3) {
                for (overflow in listOf(false, true)) {
                    val layout = widgetVerticalLayout(uniformRows(rows), overflow, budget)
                    assertTrue(
                        "2-row ${budget}dp allocation: ${layout.allocatedHeightDp}dp render exceeds it",
                        layout.allocatedHeightDp <= budget,
                    )
                    if (budget >= 82) {
                        assertEquals("$budget dp: full layout must be kept", rows, layout.gridRows)
                        assertTrue("$budget dp: full layout must keep the freshness line", layout.showFreshness)
                    }
                }
            }
        }
    }

    // --- server selection ---

    @Test
    fun unsetSelectionShowsAllServersInLiveOrder() {
        val live = listOf(srv(2, name = "b"), srv(1, online = false, name = "a"), srv(3))
        assertEquals(live.map { it.id }, resolveWidgetServers(live, null).map { it.id })
    }

    @Test
    fun explicitSelectionShowsOnlyTheSelectedInLiveOrder() {
        // Selection order must NOT reorder the widget: live order wins.
        val live = listOf(srv(1), srv(2), srv(3), srv(4))
        val shown = resolveWidgetServers(live, setOf(4, 1))
        assertEquals(listOf(1, 4), shown.map { it.id })
    }

    @Test
    fun staleSelectionIdsAreDroppedSilently() {
        // Server 7 was removed: its id is still stored in the selection.
        // The render filters the LIVE list, so 7 is dropped — never rendered,
        // never a crash — and the live servers keep their positions.
        val live = listOf(srv(1), srv(2), srv(3))
        val shown = resolveWidgetServers(live, setOf(1, 7, 3, 99))
        assertEquals(listOf(1, 3), shown.map { it.id })
    }

    @Test
    fun emptySelectionRendersNoServers() {
        val live = listOf(srv(1), srv(2))
        assertEquals(emptyList<Int>(), resolveWidgetServers(live, emptySet()).map { it.id })
        // And an all-stale selection behaves like an empty one.
        assertEquals(emptyList<Int>(), resolveWidgetServers(live, setOf(42)).map { it.id })
    }

    @Test
    fun selectionAndBudgetCompose() {
        // Selection first (what to show), budget second (what fits): 7 of 9
        // selected, two-column budget 6 → 6 rendered, "+1 more".
        val live = (1..9).map { srv(it) }
        val selected = (1..7).toSet()
        val rows = widgetGridRows(resolveWidgetServers(live, selected), WIDGET_COLUMNS_2)
        assertEquals(6, rows.sumOf { it.size })
        assertEquals(1, widgetOverflow(resolveWidgetServers(live, selected), WIDGET_COLUMNS_2))
    }
}
