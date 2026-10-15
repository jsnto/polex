package com.polex.app

/**
 * Widget display settings — the pure render logic (no Android imports, so
 * it is testable on the JVM without a device or Robolectric).
 *
 * The choices themselves live in [Prefs] (`widgetColumns` /
 * `widgetSelectedServerIds`); the settings screen (the "Widget" pane in
 * SettingsScreen) edits them and refreshes the widget on change;
 * [ServerWidget] applies them at render time for BOTH the 1×1 and 2×1
 * placements (the widget cannot read its own span from the stable options
 * API, so every rule here must hold at the tightest size — a 1×1 cell):
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
 * Max servers rendered in the grid per column mode, budgeted to the
 * tightest placement — the 1×1 cell (≈89–94 dp tall on the test devices,
 * holding the 18dp hero + 10dp freshness line + the rows at 12dp each):
 *  - 2 columns: 6 cells = 3 rows (the original 1×1 budget);
 *  - 1 column: 4 servers = 4 rows (the same vertical budget, one row each).
 * Servers past the budget collapse into the "+N more" line; the app shows
 * the full list.
 */
fun widgetMaxCells(columns: Int): Int =
    if (normalizeWidgetColumns(columns) == WIDGET_COLUMNS_1) 4 else 6

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
