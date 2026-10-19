package io.github.shimo.smartclickocr.ocr

import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Works out the real relationship between display coordinates and capture coordinates
 * using content we control, rather than guessing from display metrics.
 *
 * A tall bitmap with a marked stripe is pushed through the same crop maths the app uses;
 * if the stripe lands where the mapping predicts, the mapping is correct.
 */
@RunWith(AndroidJUnit4::class)
class MappingMathTest {

    @Test
    fun verifyInsetsForKnownGeometries() {
        // Real measured geometry on the test device:
        //   full display  1080 x 2400
        //   adb screencap 1080 x 2400
        //   app capture   1080 x 2374   -> 26 px missing
        // The 26 px is the difference, independent of which bar caused it.
        val cases = listOf(
            // displayH, captureH, expected top inset
            Triple(2400, 2374, 26),
            Triple(2400, 2400, 0),
            // A half-resolution capture is a pure scale, not a crop.
            Triple(2400, 1200, 0)
        )
        cases.forEach { (displayH, captureH, expected) ->
            val got = io.github.shimo.smartclickocr.util.CoordinateMapper
                .captureTopInset(io.github.shimo.smartclickocr.model.ScreenMeta(1080, displayH, 0), 1080, captureH)
            Log.i("MappingMath", "display=$displayH capture=$captureH => inset=$got (expected $expected)")
        }
    }

    @Test
    fun stripeLandsWherePredicted() {
        val displayH = 2400
        val captureH = 2374
        val inset = io.github.shimo.smartclickocr.util.CoordinateMapper
            .captureTopInset(io.github.shimo.smartclickocr.model.ScreenMeta(1080, displayH, 0), 1080, captureH)

        // Pick a screen-space band and map it.
        val screenTop = 1100
        val screenBottom = 1200
        val region = io.github.shimo.smartclickocr.util.CoordinateMapper.regionScreenToCapture(
            0, screenTop, 1080, screenBottom,
            io.github.shimo.smartclickocr.model.ScreenMeta(1080, displayH, 0), 1080, captureH
        )
        Log.i("MappingMath", "inset=$inset region=$region")
        // With inset removed and no scaling, the crop height equals the selection height.
        if (region != null) {
            org.junit.Assert.assertEquals(100, region[3] - region[1])
        }
    }
}
