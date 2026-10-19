package io.github.shimo.smartclickocr.util

import io.github.shimo.smartclickocr.model.ScreenMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coordinate conversion tests. These pin the rule that calibration, test clicks,
 * automation clicks and OCR cropping all use the same mapping.
 */
class CoordinateMapperTest {

    private val phone = ScreenMeta(1080, 2400, 0)

    @Test
    fun `overlay touch maps to screen pixel unchanged`() {
        val (x, y) = CoordinateMapper.overlayToScreen(123f, 456f)
        assertEquals(123f, x)
        assertEquals(456f, y)
    }

    @Test
    fun `overlay with status bar offset adds the offset`() {
        val (x, y) = CoordinateMapper.overlayToScreen(100f, 200f, offsetY = 80)
        assertEquals(100f, x)
        assertEquals(280f, y)
    }

    @Test
    fun `overlay and screen conversions are inverse`() {
        val (sx, sy) = CoordinateMapper.overlayToScreen(50f, 60f, 10, 20)
        val (vx, vy) = CoordinateMapper.screenToOverlay(sx, sy, 10, 20)
        assertEquals(50f, vx)
        assertEquals(60f, vy)
    }

    @Test
    fun `capture conversion is identity when sizes match`() {
        val (cx, cy) = CoordinateMapper.screenToCapture(500f, 900f, phone, 1080, 2400)
        assertEquals(500f, cx)
        assertEquals(900f, cy)
    }

    @Test
    fun `capture without system bars shifts coordinates up by the missing rows`() {
        // Real device case: display 1080x2400, capture 1080x2374 (26 rows omitted at the
        // top). A screen point 26 px below the top maps to the first capture row.
        val (_, topY) = CoordinateMapper.screenToCapture(0f, 26f, phone, 1080, 2374, topInset = 26)
        assertEquals(0f, topY)

        // Everything below is shifted up by exactly the inset, with no extra scaling.
        val (_, y1000) = CoordinateMapper.screenToCapture(0f, 1000f, phone, 1080, 2374, topInset = 26)
        assertEquals(974f, y1000)
    }

    @Test
    fun `capture region maps to the same pixels the user selected`() {
        // Selecting the middle band must crop the middle band of the bitmap, allowing for
        // the rows the projection omits at the top (26 px on a 2400 -> 2374 capture).
        val region = CoordinateMapper.regionScreenToCapture(
            0, 1000, 1080, 1100, phone, 1080, 2374
        )
        assertEquals(listOf(0, 974, 1080, 1074), region!!.toList())
    }

    @Test
    fun `overlay coordinates include the status bar offset`() {
        // An overlay sits below the status bar, so its y needs the bar height added.
        val (_, y) = CoordinateMapper.overlayToScreen(100f, 200f, offsetY = 63)
        assertEquals(263f, y)
        val (_, back) = CoordinateMapper.screenToOverlay(0f, 263f, offsetY = 63)
        assertEquals(200f, back)
    }

    @Test
    fun `capture conversion scales when bitmap is smaller`() {
        // A half-resolution capture must still crop the intended area (no top inset here,
        // because the bitmap is a pure 2x downscale of the full display).
        val (cx, cy) = CoordinateMapper.screenToCapture(540f, 1200f, phone, 540, 1200, topInset = 0)
        assertEquals(270f, cx)
        assertEquals(600f, cy)
    }

    @Test
    fun `region converts to capture coordinates`() {
        val region = CoordinateMapper.regionScreenToCapture(
            100, 200, 300, 400, phone, 540, 1200, topInset = 0
        )
        assertEquals(listOf(50, 100, 150, 200), region!!.toList())
    }

    @Test
    fun `region conversion clamps to bitmap bounds`() {
        val region = CoordinateMapper.regionScreenToCapture(0, 0, 5000, 5000, phone, 1080, 2400)
        assertEquals(listOf(0, 0, 1080, 2400), region!!.toList())
    }

    @Test
    fun `degenerate region returns null`() {
        assertNull(CoordinateMapper.regionScreenToCapture(100, 100, 100, 200, phone, 1080, 2400))
        // A region entirely outside the bitmap clamps to zero area and is rejected.
        assertNull(CoordinateMapper.regionScreenToCapture(2000, 3000, 2100, 3100, phone, 1080, 2400))
    }

    @Test
    fun `clamp keeps taps inside the display`() {
        val (x, y) = CoordinateMapper.clampToScreen(-20f, 5000f, phone)
        assertEquals(0f, x)
        assertEquals(2399f, y)
    }

    @Test
    fun `same screen does not need recalibration`() {
        assertFalse(CoordinateMapper.needsRecalibration(phone, ScreenMeta(1080, 2400, 0)))
    }

    @Test
    fun `rotation change needs recalibration`() {
        assertTrue(CoordinateMapper.needsRecalibration(phone, ScreenMeta(2400, 1080, 1)))
    }

    @Test
    fun `resolution change needs recalibration`() {
        assertTrue(CoordinateMapper.needsRecalibration(phone, ScreenMeta(1440, 3200, 0)))
    }

    @Test
    fun `unknown saved metadata does not nag the user`() {
        assertFalse(CoordinateMapper.needsRecalibration(ScreenMeta.EMPTY, phone))
    }

    @Test
    fun `recalibration message mentions both resolutions`() {
        val msg = CoordinateMapper.describeRecalibration(phone, ScreenMeta(2400, 1080, 1))
        assertTrue(msg.contains("1080"))
        assertTrue(msg.contains("2400"))
    }
}
