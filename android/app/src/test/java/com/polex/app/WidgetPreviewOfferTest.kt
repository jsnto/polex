package com.polex.app

import android.app.Activity
import android.content.Context
import android.content.res.XmlResourceParser
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The 1×1/2×1 widget offer, checked at the declarative contract level (the
 * product surface the launcher consumes) plus the layout structure level
 * (what the launcher's preview inflater will render):
 *
 *  - [widgetInfoDeclaresThe1x1And2x1Offer] — the widget metadata XML is the
 *    machine-consumed artifact the launcher reads to decide what sizes to
 *    offer. Parsed into a typed model (attributes resolved with unit
 *    conversion), it must declare: min 80×80dp, max 190×80dp, min/max
 *    resize 80×190dp×80dp, resizeMode horizontal ONLY (no vertical stretch,
 *    so the offer is 1×1 and 2×1 only), target cell 1×1, and the preview /
 *    initial layout pointing at `widget_preview`. 190dp is the 2-wide cap:
 *    on the Pixel 7 Pro's ≈121dp cell pitch a 2-wide offer needs
 *    max ≥ 1.5× the pitch.
 *  - [previewInflatesAndCarriesNoPlainView] — root cause of the 1×1 bug:
 *    the launcher renders `previewLayout` with its RemoteViews-based
 *    preview inflater, which refuses trees carrying classes it does not
 *    allow ("Class not allowed to be inflated" — the exact symptom when the
 *    old preview carried a plain `android.view.View` spacer, which made the
 *    launcher fall back and render the widget 1-wide × 2-tall). The inflated
 *    tree must carry no plain View node and only RemoteViews-friendly
 *    elements (LinearLayout/TextView). (The real hidden
 *    `android.app.RemoteViews` class is not shipped in Robolectric's
 *    framework jar, so the whitelist contract is asserted on the inflated
 *    tree; on-device 1×1 rendering was verified on the Pixel 7 Pro during
 *    the change's authoring.)
 *  - [gridCellsAreEqualWeightedHalvesAndHeroSurvives] — the two grid cells
 *    are equal weighted halves (weight 1, 0dp width), so the same layout
 *    reflows across the wider 2×1 offer instead of anchoring a fixed-width
 *    cell — the structure the Glance content layout (ServerWidget) uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetPreviewOfferTest {

    private fun context(): Context =
        Robolectric.buildActivity(Activity::class.java).setup().get().baseContext

    private fun walk(view: View, onNode: (View) -> Unit) {
        onNode(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) walk(view.getChildAt(i), onNode)
        }
    }

    /** Resolves a dp attribute value ("80dp") to the expected px for the
     *  current display density — a typed unit conversion, not a string match. */
    private fun dpPx(dp: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), context().resources.displayMetrics).toInt()

    @Test
    fun widgetInfoDeclaresThe1x1And2x1Offer() {
        val ctx = context()
        val res = ctx.resources
        val raw = res.getXml(R.xml.polex_widget_info)
        // Depending on the framework build, getXml() returns either the
        // parser itself or a block exposing it — handle both.
        val parser: XmlResourceParser = when {
            raw is XmlResourceParser -> raw
            else -> raw.javaClass.getMethod("getParser").invoke(raw) as XmlResourceParser
        }
        // Step to the <appwidget-provider> element.
        while (parser.eventType != XmlResourceParser.START_TAG) parser.next()
        assertEquals("appwidget-provider", parser.name)

        val a = { name: String -> parser.getAttributeValue("http://schemas.android.com/apk/res/android", name)
            ?: fail("missing android:$name in polex_widget_info.xml") }

        // The offer contract, in dp.
        assertEquals(dpPx(80), a("minWidth").toDpPx(res))
        assertEquals(dpPx(80), a("minHeight").toDpPx(res))
        assertEquals(dpPx(190), a("maxWidth").toDpPx(res))
        assertEquals(dpPx(80), a("maxHeight").toDpPx(res))
        assertEquals(dpPx(80), a("minResizeWidth").toDpPx(res))
        assertEquals(dpPx(80), a("minResizeHeight").toDpPx(res))
        assertEquals(dpPx(190), a("maxResizeWidth").toDpPx(res))
        assertEquals(dpPx(80), a("maxResizeHeight").toDpPx(res))

        // Horizontal-only stretch: the launcher may offer 2 wide but never
        // 1x2/2x2. resizeMode is a flag attribute; the runtime reports the
        // packed value, so assert the bits: RESIZE_HORIZONTAL (0x1) set,
        // RESIZE_VERTICAL (0x2) clear. (The pre-fix value was 0x3 —
        // horizontal|vertical, which is what let the launcher stretch the
        // widget down a row into the 1×2/2×2 shape behind the 1×1 bug.)
        val resizeModeRaw = a("resizeMode").trim()
        val resizeMode = if (resizeModeRaw.startsWith("0x", ignoreCase = true)) {
            resizeModeRaw.substring(2).toInt(16)
        } else {
            resizeModeRaw.toIntOrNull() ?: fail("unparseable resizeMode: $resizeModeRaw")
        }
        assertEquals(
            "resizeMode must allow horizontal stretching",
            1, resizeMode and 0x1
        )
        assertEquals(
            "resizeMode must NOT allow vertical stretching (offer is 1x1/2x1 only)",
            0, resizeMode and 0x2
        )

        // Default placement: one cell.
        assertEquals("1", a("targetCellWidth"))
        assertEquals("1", a("targetCellHeight"))

        // Refresh tick (the ≥30-min system update).
        assertEquals(1_800_000L, a("updatePeriodMillis").toLong())

        // The preview/initial layout is the RemoteViews-safe preview.
        // (The runtime reports the resolved resource id — compare it
        // against the compiled id of `widget_preview`.)
        val previewId = res.getIdentifier("widget_preview", "layout", ctx.packageName)
        assertTrue("widget_preview resource must exist", previewId != 0)
        assertEquals(previewId, a("initialLayout").resolvedId())
        assertEquals(previewId, a("previewLayout").resolvedId())
    }

    /** "@[2131362962]" / "@2131362962" (runtime-formatted resource reference) → its id. */
    private fun String.resolvedId(): Int {
        val v = trim().removePrefix("@").removeSurrounding("[", "]")
        return v.toIntOrNull() ?: fail("expected a resolved resource reference, got: $v")
    }

    @Test
    fun previewInflatesAndCarriesNoPlainView() {
        val root = LayoutInflater.from(context()).inflate(R.layout.widget_preview, null)
        assertTrue("root should be the preview LinearLayout", root is LinearLayout)
        var sawPlainView = false
        var sawDisallowed = false
        walk(root) { v ->
            // The exact class the old preview's spacer used — the class the
            // launcher's preview inflater refuses ("Class not allowed to be
            // inflated"). Must be gone from the tree.
            if (v.javaClass == View::class.java) sawPlainView = true
            // Only RemoteViews-friendly element types may appear.
            if (v.javaClass != LinearLayout::class.java && v.javaClass != TextView::class.java) {
                sawDisallowed = true
            }
        }
        assertFalse("preview tree still carries a plain android.view.View", sawPlainView)
        assertFalse("preview tree carries a launcher-disallowed element class", sawDisallowed)
    }

    @Test
    fun gridCellsAreEqualWeightedHalvesAndHeroSurvives() {
        val root = LayoutInflater.from(context()).inflate(R.layout.widget_preview, null) as LinearLayout
        // Root children: hero row (0), freshness line (1), grid row (2).
        val gridRow = root.getChildAt(2) as LinearLayout
        assertEquals("grid row must have exactly two cells", 2, gridRow.childCount)
        for (i in 0 until 2) {
            val lp = gridRow.getChildAt(i).layoutParams as LinearLayout.LayoutParams
            assertEquals("cell $i must be a weighted half (weight 1)", 1f, lp.weight, 0.001f)
            assertEquals("cell $i must use the weight (0dp width)", 0, lp.width)
        }
        // The hero text ("1/2") survives as a real TextView in the tree.
        var hero = ""
        walk(root) { v -> if (v is TextView && v.text == "1/2") hero = v.text.toString() }
        assertEquals("1/2", hero)
    }

    /** The metadata declares dp values; Robolectric reports dimension
     *  attributes formatted as "<value>dip". Parse the number and convert
     *  through the display density (a typed unit conversion, not a string
     *  match). */
    private fun String.toDpPx(res: android.content.res.Resources): Int {
        val v = trim()
        val num = v.trimEnd('d', 'i', 'p').toFloat()
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, num, res.displayMetrics).toInt()
    }
}

private fun fail(message: String): Nothing = throw AssertionError(message)
