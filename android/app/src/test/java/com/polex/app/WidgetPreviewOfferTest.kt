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
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The widget offer matrix (1×1/2×1 on every launcher, plus 1×2/2×2 on
 * short-row launchers), checked at the declarative contract level (the
 * product surface the launcher consumes) plus the layout structure level
 * (what the launcher's preview inflater will render):
 *
 *  - [widgetInfoDeclaresTheOfferMatrix] — the widget metadata XML is the
 *    machine-consumed artifact the launcher reads to decide what sizes to
 *    offer. Parsed into a typed model (attributes resolved with unit
 *    conversion), it must declare: min 40×40dp, max 190×80dp, minResize
 *    40×40dp, maxResize 190×80dp, resizeMode horizontal AND vertical
 *    (the actual per-launcher offer is capped by the min/max ranges —
 *    see below), target cell 1×1, and the preview / initial layout
 *    pointing at `widget_preview`.
 *
 *    The offer matrix the ranges produce (column range from
 *    ceil(minWidth/pitch) and the 1.5×-pitch 2-wide rule, row range from
 *    ceil(minHeight/pitch)..ceil(maxHeight/pitch), pitch = the launcher's
 *    grid pitch):
 *     - 190dp is the 2-wide cap: on the Pixel 7 Pro's ≈121dp cell pitch a
 *       2-wide offer needs max ≥ 1.5× the pitch (181.5dp ≤ 190dp);
 *     - the 40dp width minimum (minWidth AND minResizeWidth, which must
 *       agree) is the AOSP-safe floor: the launcher's picker draws a
 *       1-column tile only when the declared minimum fits its column
 *       pitch (the Pixel 7 Pro's picker pitch is in [40, 80) dp), so
 *       40dp guarantees the 1-column offer on every launcher;
 *     - the 40dp height minimum keeps the 1-row minimum on every launcher
 *       (ceil(40/pitch) = 1 for pitch ≥ 40dp) — including launchers whose
 *       row pitch is under 80dp (the One UI / Samsung S26 Ultra case,
 *       where the old 80dp floor forced a 2-row minimum and the locked
 *       80dp max hid the horizontal offer entirely, leaving only a 1×2
 *       tile); and
 *     - the 80dp height max offers 1-2 rows on short-row launchers
 *       (pitch < 80dp → 1x2/2x2 become placeable, e.g. the S26 Ultra)
 *       while tall-row launchers (pitch ≥ 80dp — the Pixel ≈116dp, the
 *       A52 ≈89dp) stay at exactly one row (ceil(80/pitch) = 1), so their
 *       offer remains 1×1 + 2×1.
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
 *  - [offerRangesPerLauncherClassMatchTheDocumentedMatrix] — the offer
 *    RANGES the metadata actually produces, computed with the launcher's
 *    grid math (column range from ceil(minWidth/pitch) and the 1.5×-pitch
 *    2-wide rule, row range from ceil(minHeight/pitch)..ceil(maxHeight/
 *    pitch)) against the parsed metadata: a short-row launcher (the S26
 *    Ultra's One UI, pitch 64dp — the documented tight case) offers 1-2 ×
 *    1-2 (1×1, 2×1, 1×2 and 2×2 all placeable — the 1×1/2×1 offers the
 *    S26 picker was missing), and tall-row launchers (Pixel ≈116/121dp,
 *    A52 ≈89dp) stay at 1-2 × exactly 1 (1×1 + 2×1, unchanged — the
 *    Pixel regression). The reported S26 failure mode (1×2-only) is
 *    reproduced against the OLD metadata values (80dp height floor) inside
 *    the test, so it fails before the floor change and passes after.
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
    fun widgetInfoDeclaresTheOfferMatrix() {
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

        // The offer contract, in dp. Both axes floor at the AOSP-safe 40dp
        // (1-column / 1-row minimum on any launcher with a ≥40dp pitch);
        // the width caps at 190dp (2 columns) and the height at 80dp
        // (1-2 rows on short-row launchers, exactly 1 row on tall-row
        // launchers — see the class KDoc for the offer matrix).
        assertEquals(dpPx(40), a("minWidth").toDpPx(res))
        assertEquals(dpPx(40), a("minHeight").toDpPx(res))
        assertEquals(dpPx(190), a("maxWidth").toDpPx(res))
        assertEquals(dpPx(80), a("maxHeight").toDpPx(res))
        assertEquals(dpPx(40), a("minResizeWidth").toDpPx(res))
        assertEquals(dpPx(40), a("minResizeHeight").toDpPx(res))
        assertEquals(dpPx(190), a("maxResizeWidth").toDpPx(res))
        assertEquals(dpPx(80), a("maxResizeHeight").toDpPx(res))

        // The widget may be stretched across columns AND down a row —
        // the per-launcher offer range is capped by the min/max ranges
        // above (1-2 x 1 on tall-row launchers, 1-2 x 1-2 on short-row
        // launchers). resizeMode is a flag attribute; the runtime reports
        // the packed value, so assert the bits: RESIZE_HORIZONTAL (0x1)
        // set and RESIZE_VERTICAL (0x2) set.
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
            "resizeMode must allow vertical stretching (1x2/2x2 on short-row launchers)",
            2, resizeMode and 0x2
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

    @Test
    fun offerRangesPerLauncherClassMatchTheDocumentedMatrix() {
        val res = context().resources
        val raw = res.getXml(R.xml.polex_widget_info)
        // Depending on the framework build, getXml() returns either the
        // parser itself or a block exposing it — handle both.
        val parser: XmlResourceParser = when {
            raw is XmlResourceParser -> raw
            else -> raw.javaClass.getMethod("getParser").invoke(raw) as XmlResourceParser
        }
        while (parser.eventType != XmlResourceParser.START_TAG) parser.next()
        assertEquals("appwidget-provider", parser.name)

        val a = { name: String -> parser.getAttributeValue("http://schemas.android.com/apk/res/android", name)
            ?: fail("missing android:$name in polex_widget_info.xml") }

        // The declared offer ranges in dp (typed values, not strings).
        val minW = a("minWidth").dpValue()
        val minH = a("minHeight").dpValue()
        val maxW = a("maxWidth").dpValue()
        val maxH = a("maxHeight").dpValue()

        // The launcher's grid math (the documented model — see the metadata
        // comment): column range ceil(minWidth/pitch) .. 2 (a 2-wide offer
        // exists when maxWidth ≥ 1.5× the pitch), row range
        // ceil(minHeight/pitch) .. ceil(maxHeight/pitch). Both stretch bits
        // must be set for the row range to be offerable at all.
        val resizeMode = a("resizeMode").trim().let {
            if (it.startsWith("0x", ignoreCase = true)) it.substring(2).toInt(16)
            else it.toIntOrNull() ?: fail("unparseable resizeMode: $it")
        }
        assertTrue("both resize bits must be set for 1x2/2x2 offers", resizeMode and 0x3 == 0x3)

        val offer = { pitch: Int ->
            val colMin = ceil(minW / pitch.toFloat()).toInt()
            val colMax = if (maxW >= 1.5f * pitch) 2 else 1
            val rowMin = ceil(minH / pitch.toFloat()).toInt()
            val rowMax = ceil(maxH / pitch.toFloat()).toInt()
            (colMin..colMax) to (rowMin..rowMax)
        }

        // The S26 Ultra One UI short row (pitch 64dp — the documented tight
        // case): 1-2 columns × 1-2 rows — 1×1, 2×1, 1×2 AND 2×2 are all
        // placeable. The 1×1 and 2×1 offers are the ones the S26 picker
        // was missing before this change.
        val (s26Cols, s26Rows) = offer(64)
        assertEquals("S26 column range", 1..2, s26Cols)
        assertEquals("S26 row range", 1..2, s26Rows)
        // The reported failure, reproduced against the OLD metadata (80dp
        // height floor AND 80dp max): a 64dp short row's minimum is two
        // rows and its maximum is two rows — the row range locks at 2, so
        // the 1-row (1×1/2×1) offers never appear (the 1×2-only picker).
        val oldRowMin = ceil(80f / 64f).toInt()
        val oldRowMax = ceil(80f / 64f).toInt()
        assertEquals("old metadata's S26 row range must lock at two rows", 2..2, oldRowMin..oldRowMax)
        // The new 40dp floor un-locks it: the 1-row minimum appears.
        assertEquals(1, ceil(40f / 64f).toInt())
        // The AOSP-safe 40dp pitch floor: still 1-2 × 1-2.
        assertEquals(1..2, offer(40).first)
        assertEquals(1..2, offer(40).second)

        // Every short-row pitch in the offer range (40..79dp): a 2-row
        // offer exists (row range ends at 2) and its allocation 2×pitch ≥
        // 2×40dp = 80dp, so the content's fit rules always fit it (the
        // 2×pitch ≥ 80dp → full-or-compact pin lives in WidgetContentTest).
        for (pitch in 40..79) {
            val (cols, rows) = offer(pitch)
            assertEquals("$pitch dp: 2-row offer must exist on short-row launchers", 2, rows.last)
            assertTrue("$pitch dp: 2-row allocation must be ≥80dp", 2 * pitch >= 80)
            assertTrue("$pitch dp: 1-column offer must survive", cols.first == 1)
        }

        // Tall-row launchers (pitch ≥ 80dp — the Pixel 7 Pro ≈116/121dp
        // picker pitch, the A52 ≈89dp): exactly one row is offered (no
        // 1×2/2×2) and the 2-wide offer survives (190dp ≥ 1.5× the pitch),
        // so their offer stays 1×1 + 2×1 — the Pixel 7 Pro regression.
        for (pitch in listOf(80, 89, 116, 121)) {
            val (cols, rows) = offer(pitch)
            assertEquals("$pitch dp: column range must stay 1-2", 1..2, cols)
            assertEquals("$pitch dp: tall-row launchers must stay at exactly one row", 1..1, rows)
        }
    }

    /** "40dp"/"40dip"/"40.0dip" → 40 (the declared dimension value in dp). */
    private fun String.dpValue(): Int =
        trim().trimEnd('d', 'i', 'p').toFloatOrNull()?.roundToInt()
            ?: fail("unparseable dp value: $this")

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
