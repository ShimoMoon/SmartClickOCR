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
 * Measures the real vertical offset between the screenshot and the display by locating
 * the same distinctive text in both.
 *
 * "input.png" is a screencap taken by the system (full display size). "app.png" is what
 * MediaProjection returned (if provided). For each, the Y of a known string is found by
 * sliding a window; the difference is the offset the crop maths must apply.
 */
@RunWith(AndroidJUnit4::class)
class OffsetCalibrationTest {

    private fun recognize(bitmap: Bitmap): String {
        val recognizer = com.google.mlkit.vision.text.TextRecognition
            .getClient(com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions.Builder().build())
        val latch = java.util.concurrent.CountDownLatch(1)
        var result = ""
        recognizer.process(com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result = (it.text ?: "").replace("\n", " "); latch.countDown() }
            .addOnFailureListener { result = "ERR"; latch.countDown() }
        latch.await(20, java.util.concurrent.TimeUnit.SECONDS)
        runCatching { recognizer.close() }
        return result
    }

    /** Returns the centre Y of the first window whose text contains [needle]. */
    private fun findY(full: Bitmap, needle: String, winH: Int = 100, step: Int = 25): Int? {
        var y = 0
        while (y + winH <= full.height) {
            val crop = Bitmap.createBitmap(full, 0, y, full.width, winH)
            val text = recognize(crop)
            crop.recycle()
            if (text.contains(needle)) return y + winH / 2
            y += step
        }
        return null
    }

    @Test
    fun measure() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.filesDir, "ocr-test")
        val sys = File(dir, "input.png")   // system screencap: true screen coordinates
        val app = File(dir, "app.png")     // MediaProjection frame, if available
        if (!sys.exists()) {
            Log.w("OffsetCal", "no input.png")
            return
        }

        val sysBmp = BitmapFactory.decodeFile(sys.absolutePath) ?: return
        Log.i("OffsetCal", "sys screencap = ${sysBmp.width}x${sysBmp.height}")

        // A string that appears exactly once near the middle of the reference image.
        // Replace with any distinctive label from your own test screen.
        val needle = "Notifications"
        val sysY = findY(sysBmp, needle)
        Log.i("OffsetCal", "system  Y($needle) = $sysY")
        sysBmp.recycle()

        if (app.exists()) {
            val appBmp = BitmapFactory.decodeFile(app.absolutePath)
            if (appBmp != null) {
                Log.i("OffsetCal", "app capture  = ${appBmp.width}x${appBmp.height}")
                val appY = findY(appBmp, needle)
                Log.i("OffsetCal", "app     Y($needle) = $appY")
                if (sysY != null && appY != null) {
                    Log.i("OffsetCal", "OFFSET = ${sysY - appY} px (positive: capture content sits higher)")
                }
                appBmp.recycle()
            }
        }
    }
}
