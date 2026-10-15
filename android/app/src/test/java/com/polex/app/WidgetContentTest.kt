package com.polex.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Widget display settings — the render logic (WidgetContent.kt) the widget
 * applies in BOTH the 1×1 and 2×1 placements:
 *
 *  - column modes: 2 columns (default) = dot + name pairs per row, 1 column
 *    = one row per server, and the per-mode 1×1 vertical budget (6 cells
 *    vs 4 rows) plus the "+N more" overflow count;
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
        // 2 columns: 6 cells = 3 rows; 1 column: 4 rows — both sized to the
        // tightest placement (1×1: hero + freshness + the rows at 12dp each).
        assertEquals(6, widgetMaxCells(WIDGET_COLUMNS_2))
        assertEquals(4, widgetMaxCells(WIDGET_COLUMNS_1))
        // Out-of-range modes budget the default (2-column) size.
        assertEquals(6, widgetMaxCells(7))
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
        val live = (1..4).map { srv(it) }
        val rows = widgetGridRows(live, WIDGET_COLUMNS_1)
        assertEquals(4, rows.size)
        assertEquals(live.map { it.id }, rows.map { row -> row.single().id })
    }

    @Test
    fun bothModesCapAtTheBudgetAndReportTheOverflow() {
        val live = (1..9).map { srv(it) }
        val two = widgetGridRows(live, WIDGET_COLUMNS_2)
        assertEquals(3, two.size) // 6 cells capped
        assertEquals(3, widgetOverflow(live, WIDGET_COLUMNS_2)) // "+3 more"

        val one = widgetGridRows(live, WIDGET_COLUMNS_1)
        assertEquals(4, one.size) // 4 rows capped
        assertEquals(5, widgetOverflow(live, WIDGET_COLUMNS_1)) // "+5 more"

        // Nothing past the budget → no overflow line.
        assertEquals(0, widgetOverflow(live.take(6), WIDGET_COLUMNS_2))
        assertEquals(0, widgetOverflow(live.take(4), WIDGET_COLUMNS_1))
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
