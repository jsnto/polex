package com.polex.app

import android.app.Activity
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The widget display settings' persistence contract (Prefs.kt):
 *
 *  - column mode: defaults to 2 (the original look), round-trips 1, and a
 *    corrupted raw value reads back as the 2-column default (the getter
 *    normalizes, so the widget never sees an unknown mode);
 *  - server selection: `null` (unset) = "show all" — distinct from a
 *    stored EMPTY set ("show nothing", a deliberate choice); non-empty
 *    sets round-trip their ids exactly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrefsWidgetSettingsTest {

    private fun context(): Context =
        Robolectric.buildActivity(Activity::class.java).setup().get().baseContext

    @Test
    fun columnModeDefaultsToTwoColumns() {
        assertEquals(WIDGET_COLUMNS_2, Prefs.widgetColumns(context()))
    }

    @Test
    fun columnModeRoundTrips() {
        val ctx = context()
        Prefs.setWidgetColumns(ctx, WIDGET_COLUMNS_1)
        assertEquals(WIDGET_COLUMNS_1, Prefs.widgetColumns(ctx))
        Prefs.setWidgetColumns(ctx, WIDGET_COLUMNS_2)
        assertEquals(WIDGET_COLUMNS_2, Prefs.widgetColumns(ctx))
    }

    @Test
    fun outOfRangeColumnModeReadsBackAsTheDefault() {
        // The setter normalizes too; write the raw key to simulate a
        // corrupted stored value and confirm the getter still returns a
        // supported mode.
        val ctx = context()
        Prefs.setWidgetColumns(ctx, WIDGET_COLUMNS_1)
        ctx.getSharedPreferences("polex", Context.MODE_PRIVATE)
            .edit().putInt("widgetColumns", 9).apply()
        assertEquals(WIDGET_COLUMNS_2, Prefs.widgetColumns(ctx))
    }

    @Test
    fun selectionIsUnsetByDefault() {
        // Unset (null) is the "show all" default — NOT an empty set.
        assertNull(Prefs.widgetSelectedServerIds(context()))
    }

    @Test
    fun selectionRoundTripsItsIds() {
        val ctx = context()
        Prefs.setWidgetSelectedServerIds(ctx, setOf(3, 1))
        val stored = Prefs.widgetSelectedServerIds(ctx)
        assertNotNull(stored)
        assertEquals(setOf(1, 3), stored)
        Prefs.setWidgetSelectedServerIds(ctx, setOf(2))
        assertEquals(setOf(2), Prefs.widgetSelectedServerIds(ctx))
    }

    @Test
    fun clearingTheSelectionRestoresTheUnsetDefault() {
        // "All" (the settings pane) clears the stored selection: it must
        // read back UNSET (null) — not as an empty set ("show nothing").
        val ctx = context()
        Prefs.setWidgetSelectedServerIds(ctx, setOf(1, 2))
        assertEquals(setOf(1, 2), Prefs.widgetSelectedServerIds(ctx))
        Prefs.clearWidgetSelectedServerIds(ctx)
        assertNull("cleared selection reads back unset (null), not an empty set", Prefs.widgetSelectedServerIds(ctx))
    }

    @Test
    fun emptySelectionIsStoredAndDistinctFromUnset() {
        // "Show nothing" is a real choice (the settings screen's None),
        // and the widget must honor it (render no servers) without
        // falling back to the show-all default.
        val ctx = context()
        assertNull("precondition: unset by default", Prefs.widgetSelectedServerIds(ctx))
        Prefs.setWidgetSelectedServerIds(ctx, emptySet())
        val stored = Prefs.widgetSelectedServerIds(ctx)
        assertNotNull("an explicitly stored empty set must read back (not as unset)", stored)
        assertEquals("empty selection means show nothing, not show all", emptySet<Int>(), stored)
    }
}
