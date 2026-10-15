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
 *    documented tight case (the Samsung One UI short-row host); the
 *    budget itself is the host-reported allocation (OPTION_APPWIDGET_SIZES,
 *    API 31+), falling back to the 89 dp default when the host reports
 *    none;
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
            val worstCaseDp = widgetLayoutHeightDp(rows, freshness = true, overflow = true)
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
        // 18 hero + [10 freshness] + 12/row + [10 overflow] + 8 padding.
        assertEquals(82, widgetLayoutHeightDp(3, true, true))  // full worst case
        assertEquals(72, widgetLayoutHeightDp(3, false, true)) // compact worst case
        assertEquals(62, widgetLayoutHeightDp(3, false, false))
        assertEquals(50, widgetLayoutHeightDp(2, false, false))
        assertEquals(48, widgetLayoutHeightDp(1, true, false))
        assertEquals(58, widgetLayoutHeightDp(1, true, true))
        assertEquals(26, widgetLayoutHeightDp(0, false, false)) // hero + padding only
    }

    @Test
    fun defaultBudgetAlwaysKeepsTheFullLayout() {
        // 89 dp default (the tightest historically measured cell): the full
        // worst case (82 dp) fits every shape, so the historical render —
        // freshness line kept, no dropped rows — is unchanged on the Pixel
        // 7 Pro (≈116 dp cells), the A52 and every unknown host.
        for (rows in 0..3) {
            for (overflow in listOf(false, true)) {
                val layout = widgetVerticalLayout(rows, overflow, WIDGET_HOST_BUDGET_DEFAULT_DP)
                assertEquals(rows, layout.gridRows)
                assertTrue(layout.showFreshness)
                assertEquals(widgetLayoutHeightDp(rows, true, overflow), layout.allocatedHeightDp)
            }
        }
    }

    @Test
    fun shortBudgetKeepsTheFreshnessLineOnlyWhenTheFullLayoutFits() {
        // 64 dp tight case: 1 row full (48 dp, 58 with overflow) fits —
        // small widgets keep the freshness line on the short host too.
        val one = widgetVerticalLayout(1, false, WIDGET_HOST_BUDGET_SHORT_DP)
        assertTrue(one.showFreshness)
        assertEquals(1, one.gridRows)
        assertEquals(48, one.allocatedHeightDp)
        val oneMore = widgetVerticalLayout(1, true, WIDGET_HOST_BUDGET_SHORT_DP)
        assertTrue(oneMore.showFreshness)
        assertEquals(58, oneMore.allocatedHeightDp)
        // 2 rows full = 60 dp ≤ 64 → the full layout (freshness kept) is
        // kept even at the 64 dp tight row.
        val two = widgetVerticalLayout(2, false, WIDGET_HOST_BUDGET_SHORT_DP)
        assertTrue(two.showFreshness)
        assertEquals(2, two.gridRows)
        assertEquals(60, two.allocatedHeightDp)
        // 2 rows + overflow full = 70 dp > 64 → compact: 60 dp (the
        // secondary line drops, the rows stay).
        val twoMore = widgetVerticalLayout(2, true, WIDGET_HOST_BUDGET_SHORT_DP)
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
        val layout = widgetVerticalLayout(3, true, WIDGET_HOST_BUDGET_SHORT_DP)
        assertEquals(2, layout.gridRows)
        assertFalse(layout.showFreshness)
        assertEquals(60, layout.allocatedHeightDp)
        // 3 rows WITHOUT overflow: compact 62 dp fits → the rows are kept
        // and only the secondary freshness line drops.
        val noOverflow = widgetVerticalLayout(3, false, WIDGET_HOST_BUDGET_SHORT_DP)
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
        val layout = widgetVerticalLayout(3, false, 50)
        assertEquals(1, layout.gridRows)
        assertFalse(layout.showFreshness)
        // The guaranteed overflow line is priced into the returned height:
        // 48 dp = hero + 1 row + overflow line + padding, not the 50 dp
        // unpriced shape (whose render would add a 10dp line the decision
        // never priced, clipping the allocation).
        assertEquals(widgetLayoutHeightDp(1, false, true), layout.allocatedHeightDp)
        // Same invariant at a 56 dp allocation (short-row host):
        // 1 row + overflow line = 48 dp ≤ 56.
        val taller = widgetVerticalLayout(3, false, 56)
        assertEquals(1, taller.gridRows)
        assertFalse(taller.showFreshness)
        assertEquals(48, taller.allocatedHeightDp)
    }

    @Test
    fun shortBudgetLayoutNeverExceedsTheBudget() {
        // Every shape at the 64 dp tight case fits the budget.
        for (rows in 0..3) {
            for (overflow in listOf(false, true)) {
                val layout = widgetVerticalLayout(rows, overflow, WIDGET_HOST_BUDGET_SHORT_DP)
                assertTrue(
                    "$rows rows/$overflow overflow exceeds the budget",
                    layout.allocatedHeightDp <= WIDGET_HOST_BUDGET_SHORT_DP,
                )
            }
        }
        // A degenerate 36 dp budget (below any real row pitch — the AOSP-
        // safe 40dp floor) degrades to the hero-only render, never above
        // the budget.
        val tiny = widgetVerticalLayout(3, true, 36)
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
        // A degenerate non-positive allocation is treated as absent.
        assertEquals(WIDGET_HOST_BUDGET_DEFAULT_DP, widgetBudgetDp(0))
        assertEquals(WIDGET_HOST_BUDGET_DEFAULT_DP, widgetBudgetDp(-12))
    }

    @Test
    fun everyOfferedAllocationRendersWithinBudget() {
        // The offer matrix can produce exactly these allocations: a 1-row
        // cell of 40..80dp (the declared height range) or a 2-row cell of
        // 2×pitch on a short-row launcher (pitch 40..79dp → 80..158dp).
        // Every layout decision at any of them must fit the allocation —
        // "the content must reflow and render cleanly at EVERY offered
        // size": no clip anywhere in the offer range, in any row/overflow
        // shape.
        val budgets = (40..80).toSet() + (40..79).map { 2 * it }.toSet()
        for (budget in budgets) {
            for (rows in 0..3) {
                for (overflow in listOf(false, true)) {
                    val layout = widgetVerticalLayout(rows, overflow, budget)
                    assertTrue(
                        "$rows rows / overflow=$overflow at a ${budget}dp allocation: " +
                            "the ${layout.allocatedHeightDp}dp render exceeds the allocation",
                        layout.allocatedHeightDp <= budget,
                    )
                }
            }
        }
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
                    val layout = widgetVerticalLayout(rows, overflow, budget)
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
