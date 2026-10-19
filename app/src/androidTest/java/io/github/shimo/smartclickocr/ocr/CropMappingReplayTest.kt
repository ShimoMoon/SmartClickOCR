package io.github.shimo.smartclickocr.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.shimo.smartclickocr.util.CoordinateMapper
import io.github.shimo.smartclickocr.util.ScreenMetricsProvider
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Replays a user selection through the REAL crop mapping and reports which text is
 * actually read, so the mapping can be checked against what the user sees.
 */
@RunWith(AndroidJUnit4::class)
class CropMappingReplayTest {

    private fun recognize(bitmap: Bitmap): String {
        val recognizer = com.google.mlkit.vision.text.TextRecognition
            .getClient(com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions.Builder().build())
        val latch = java.util.concurrent.CountDownLatch(1)
        var result = ""
        recognizer.process(com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result = (it.text ?: "").replace("\n", " / "); latch.countDown() }
            .addOnFailureListener { result = "ERR"; latch.countDown() }
        latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
        runCatching { recognizer.close() }
        return result
    }

    @Test
    fun replaySelection() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ScreenMetricsProvider.attach(ctx)
        val input = File(File(ctx.filesDir, "ocr-test"), "input.png")
        if (!input.exists()) return
        val full = BitmapFactory.decodeFile(input.absolutePath) ?: return

        val screen = ScreenMetricsProvider.realDisplaySize()
        val insets = ScreenMetricsProvider.systemBarInsets()
        Log.i("CropReplay", "screen=${screen.widthPx}x${screen.heightPx} capture=${full.width}x${full.height} " +
            "insetTop=${insets.top} insetBottom=${insets.bottom}")

        // Simulate the user selecting the 星 glyph band: on screen it sits at roughly
        // y = 1080..1200 measured in the CAPTURE. Convert that back to a screen-space
        // selection with the inverse of the mapping, then re-apply the forward mapping to
        // see whether the crop returns the same band.
        val captureTop = 1080
        val captureBottom = 1200

        // What the user's overlay would report (screen space, status bar included).
        val statusTop = insets.top
        val screenTop = captureTop + statusTop
        val screenBottom = captureBottom + statusTop
        Log.i("CropReplay", "assumed user selection (screen space): y=$screenTop..$screenBottom")

        val region = CoordinateMapper.regionScreenToCapture(
            0, screenTop, full.width, screenBottom, screen, full.width, full.height
        )
        Log.i("CropReplay", "mapped crop region = ${region?.toList()}  (expected ~[0,$captureTop,${full.width},$captureBottom])")

        if (region != null) {
            val crop = Bitmap.createBitmap(
                full, region[0], region[1], region[2] - region[0], region[3] - region[1]
            )
            Log.i("CropReplay", "text read from mapped crop => [${recognize(crop)}]")
            crop.recycle()
        }
        full.recycle()
    }
}
