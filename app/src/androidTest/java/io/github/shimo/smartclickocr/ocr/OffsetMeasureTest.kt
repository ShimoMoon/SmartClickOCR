package io.github.shimo.smartclickocr.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures the exact vertical offset between a screen-space Y and the same content in
 * the captured frame.
 *
 * It slides a fixed-height window down the screenshot and records which Y values yield
 * recognised text. Comparing those ranges with where the content visually sits in the
 * screenshot gives the offset directly, instead of guessing from display metrics.
 */
@RunWith(AndroidJUnit4::class)
class OffsetMeasureTest {

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
    fun slideWindow() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(File(ctx.filesDir, "ocr-test"), "input.png")
        if (!input.exists()) return
        val full = BitmapFactory.decodeFile(input.absolutePath) ?: return
        Log.i("OffsetMeasure", "screenshot=${full.width}x${full.height}")

        // Slide a 120-px tall window in 60-px steps and report recognised text.
        val winH = 120
        var y = 0
        while (y + winH <= full.height) {
            val crop = Bitmap.createBitmap(full, 0, y, full.width, winH)
            val text = recognize(crop)
            if (text.isNotBlank() && text != "ERR") {
                Log.i("OffsetMeasure", "WIN y=$y..${y + winH} => [$text]")
            }
            crop.recycle()
            y += 60
        }
        full.recycle()
    }
}
