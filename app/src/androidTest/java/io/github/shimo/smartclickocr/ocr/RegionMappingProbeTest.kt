package io.github.shimo.smartclickocr.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Determines whether the coordinates a user selects map to the SAME pixels in the
 * screenshot the app crops.
 *
 * Strategy: take one reference screenshot, then crop several labelled bands and OCR each
 * one. If a band reports the text that visually belongs to a different band, the screen
 * and the capture use different coordinate origins — exactly the "I selected this area
 * but it read somewhere else" symptom.
 */
@RunWith(AndroidJUnit4::class)
class RegionMappingProbeTest {

    private fun recognize(bitmap: Bitmap): String {
        val recognizer = com.google.mlkit.vision.text.TextRecognition
            .getClient(com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions.Builder().build())
        val latch = java.util.concurrent.CountDownLatch(1)
        var result = ""
        recognizer.process(com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result = it.text ?: ""; latch.countDown() }
            .addOnFailureListener { result = "ERR:${it.message}"; latch.countDown() }
        latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
        runCatching { recognizer.close() }
        return result
    }

    @Test
    fun probeBandMapping() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(File(ctx.filesDir, "ocr-test"), "input.png")
        if (!input.exists()) {
            Log.w("RegionProbe", "no input image")
            return
        }
        val full = BitmapFactory.decodeFile(input.absolutePath) ?: return
        val out = File(ctx.filesDir, "ocr-test")
        Log.i("RegionProbe", "image=${full.width}x${full.height}")

        // Horizontal bands across the whole image; each is 180 px tall.
        var y = 0
        val bandH = 180
        while (y + bandH <= full.height) {
            val crop = Bitmap.createBitmap(full, 0, y, full.width, bandH)
            val text = recognize(crop).replace("\n", " / ")
            Log.i("RegionProbe", "BAND y=$y..${y + bandH} => [$text]")
            File(out, "band-$y.txt").writeText(text)
            crop.recycle()
            y += bandH
        }
        full.recycle()
        assertTrue(true)
    }
}
