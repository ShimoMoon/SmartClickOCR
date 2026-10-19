package io.github.shimo.smartclickocr.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.shimo.smartclickocr.util.ScreenMetricsProvider
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prints the geometry values the app actually uses, so the calibration frame and the
 * capture frame can be compared directly.
 */
@RunWith(AndroidJUnit4::class)
class GeometryProbeTest {

    @Test
    fun printGeometry() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ScreenMetricsProvider.attach(ctx)

        val real = ScreenMetricsProvider.realDisplaySize()
        val current = ScreenMetricsProvider.current()
        val insets = ScreenMetricsProvider.systemBarInsets()

        Log.i("GeometryProbe", "realDisplaySize = ${real.widthPx}x${real.heightPx} rot=${real.rotation}")
        Log.i("GeometryProbe", "current         = ${current.widthPx}x${current.heightPx} rot=${current.rotation}")
        Log.i("GeometryProbe", "systemBarInsets top=${insets.top} bottom=${insets.bottom} left=${insets.left} right=${insets.right}")

        // What does the test screenshot look like?
        val input = File(File(ctx.filesDir, "ocr-test"), "input.png")
        if (input.exists()) {
            val bmp: Bitmap? = BitmapFactory.decodeFile(input.absolutePath)
            Log.i("GeometryProbe", "screenshot      = ${bmp?.width}x${bmp?.height}")
            bmp?.recycle()
        }
    }
}
