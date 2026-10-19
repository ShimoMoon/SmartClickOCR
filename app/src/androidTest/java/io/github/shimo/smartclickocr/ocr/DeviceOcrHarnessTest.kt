package io.github.shimo.smartclickocr.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device OCR harness. Reads a PNG from /sdcard/ocr-test/input.png, crops the given
 * rectangle, and writes the recognised text next to it. This isolates "does ML Kit
 * recognise this bitmap" from MediaProjection entirely.
 */
@RunWith(AndroidJUnit4::class)
class DeviceOcrHarnessTest {

    private fun recognize(bitmap: Bitmap): String {
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val latch = CountDownLatch(1)
        var result = ""
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text ->
                result = text.text ?: ""
                latch.countDown()
            }
            .addOnFailureListener { e ->
                result = "ERROR: ${e.message}"
                latch.countDown()
            }
        latch.await(30, TimeUnit.SECONDS)
        runCatching { recognizer.close() }
        return result
    }

    @Test
    fun recogniseProvidedImage() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        // App-private dir: scoped storage blocks reading /sdcard from here.
        val out = File(ctx.filesDir, "ocr-test")
        out.mkdirs()
        val input = File(out, "input.png")

        if (!input.exists()) {
            Log.w("DeviceOcrHarness", "no input image at ${input.absolutePath}")
            return
        }

        val full = BitmapFactory.decodeFile(input.absolutePath)
            ?: error("cannot decode ${input.absolutePath}")
        DirectoryMarker.mark(out, "image=${full.width}x${full.height}")

        // Whole image first.
        val whole = recognize(full)
        File(out, "result-whole.txt").writeText(whole)
        Log.i("DeviceOcrHarness", "WHOLE => $whole")

        // Regions around the single 星 glyph in the middle (image is 1080x2374), plus the
        // surrounding lines, to see what a user selection actually yields.
        val regions = listOf(
            // Tight box around the glyph itself.
            intArrayOf(440, 830, 640, 1060),
            // Looser selection: the glyph card and its surroundings.
            intArrayOf(430, 790, 660, 1090),
            // Very tight: only the glyph strokes.
            intArrayOf(480, 880, 600, 1010),
            // The instruction line above the glyph.
            intArrayOf(360, 700, 720, 780),
            // The line below the glyph.
            intArrayOf(300, 1090, 780, 1170)
        )
        regions.forEachIndexed { index, r ->
            val l = r[0].coerceIn(0, full.width - 2)
            val t = r[1].coerceIn(0, full.height - 2)
            val rr = r[2].coerceIn(l + 1, full.width)
            val bb = r[3].coerceIn(t + 1, full.height)
            val crop = Bitmap.createBitmap(full, l, t, rr - l, bb - t)
            val text = recognize(crop)
            File(out, "result-region$index.txt").writeText("region=($l,$t,$rr,$bb)\n$text")
            Log.i("DeviceOcrHarness", "REGION$index ($l,$t,$rr,$bb) ${rr - l}x${bb - t} => [$text]")
            crop.recycle()
        }
        full.recycle()

        // Experiment: the single big 星 glyph fails on its own. Test whether scaling the
        // crop to a "normal" text size, or adding background margin, helps; and whether
        // including context (the neighbouring line) does.
        val again = BitmapFactory.decodeFile(input.absolutePath)
        if (again != null) {
            experiment(again, out)
            contextExperiment(again, out)
            matchExperiment(again, out)
            again.recycle()
        }
        assertTrue(true)
    }

    /**
     * Verifies the end-to-end matching rule: with a wide-enough region the target 星 must
     * be found even though the detector merges it with neighbouring lines.
     */
    private fun matchExperiment(full: Bitmap, out: File) {
        val regions = mapOf(
            "wide" to intArrayOf(330, 680, 750, 1200),
            "mid" to intArrayOf(0, 500, 1080, 1400),
            "tight" to intArrayOf(440, 830, 640, 1060)
        )
        val target = "星"
        regions.forEach { (label, r) ->
            val l = r[0].coerceIn(0, full.width - 2)
            val t = r[1].coerceIn(0, full.height - 2)
            val rr = r[2].coerceIn(l + 1, full.width)
            val bb = r[3].coerceIn(t + 1, full.height)
            val crop = Bitmap.createBitmap(full, l, t, rr - l, bb - t)

            val recognizer = com.google.mlkit.vision.text.TextRecognition
                .getClient(com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions.Builder().build())
            val latch = java.util.concurrent.CountDownLatch(1)
            var verdict = "未执行"
            var text = ""
            recognizer.process(com.google.mlkit.vision.common.InputImage.fromBitmap(crop, 0))
                .addOnSuccessListener { res ->
                    text = res.text ?: ""
                    // Build blocks the same way OcrManager does, then apply the matcher.
                    val blocks = res.textBlocks.flatMap { b ->
                        if (b.lines.isNotEmpty()) b.lines.map { l2 ->
                            OcrMatcher.TextBlock(l2.text, 0, 0, 0, 0)
                        } else listOf(OcrMatcher.TextBlock(b.text, 0, 0, 0, 0))
                    }
                    val r2 = OcrMatcher.evaluateBlocks(blocks, text, target)
                    val byWhole = OcrMatcher.evaluateBlocks(emptyList(), text, target)
                    verdict = "blocks=${r2 == io.github.shimo.smartclickocr.model.ActionResult.StopMatched}" +
                        " wholeOnly=${byWhole == io.github.shimo.smartclickocr.model.ActionResult.StopMatched}"
                    latch.countDown()
                }
                .addOnFailureListener { latch.countDown() }
            latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
            runCatching { recognizer.close() }
            Log.i("DeviceOcrHarness", "MATCH $label => $verdict | text=[${text.replace("\n", " / ")}]")
            File(out, "match-$label.txt").writeText("$verdict\n$text")
            crop.recycle()
        }
    }

    /**
     * Checks whether giving the detector surrounding text makes the isolated glyph
     * recognisable — this decides how the app should guide region selection.
     */
    private fun contextExperiment(full: Bitmap, out: File) {
        val variants = mapOf(
            // glyph + the line above it
            "glyph+above" to intArrayOf(360, 700, 720, 1060),
            // glyph + the line below it
            "glyph+below" to intArrayOf(300, 830, 780, 1170),
            // glyph + both neighbours
            "glyph+both" to intArrayOf(300, 700, 780, 1170),
            // whole card area with generous margins
            "card-wide" to intArrayOf(330, 680, 750, 1200),
            // most of the screen
            "screen-mid" to intArrayOf(0, 500, 1080, 1400)
        )
        variants.forEach { (label, r) ->
            val l = r[0].coerceIn(0, full.width - 2)
            val t = r[1].coerceIn(0, full.height - 2)
            val rr = r[2].coerceIn(l + 1, full.width)
            val bb = r[3].coerceIn(t + 1, full.height)
            val crop = Bitmap.createBitmap(full, l, t, rr - l, bb - t)
            val text = recognize(crop)
            Log.i("DeviceOcrHarness", "CTX $label ${rr - l}x${bb - t} => [$text]")
            File(out, "ctx-$label.txt").writeText(text)
            crop.recycle()
        }
    }

    /** Runs a matrix of crop/padding/scale variants over the 星 glyph. */
    private fun experiment(full: Bitmap, out: File) {
        // The glyph occupies roughly this box in the 1080x2374 screenshot.
        val l = 440; val t = 830; val rr = 640; val bb = 1060
        val crop = Bitmap.createBitmap(full, l, t, rr - l, bb - t)

        fun report(label: String, bmp: Bitmap) {
            val text = recognize(bmp)
            Log.i("DeviceOcrHarness", "EXP $label => [$text]")
            File(out, "exp-$label.txt").writeText(text)
        }

        // Baseline.
        report("baseline", crop)

        // Scaled down to a normal-looking glyph height (~48 px like body text).
        listOf(0.25f, 0.4f, 0.6f).forEach { s ->
            val w = (crop.width * s).toInt().coerceAtLeast(1)
            val h = (crop.height * s).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(crop, w, h, true)
            report("scale${(s * 100).toInt()}", scaled)
            scaled.recycle()
        }

        // Same, but embedded in a wide light canvas so the detector sees a text line.
        listOf(0.4f, 0.25f).forEach { s ->
            val w = (crop.width * s).toInt().coerceAtLeast(1)
            val h = (crop.height * s).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(crop, w, h, true)
            val padX = h * 2
            val padY = (h * 0.6f).toInt()
            val canvas = Bitmap.createBitmap(w + padX * 2, h + padY * 2, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(canvas)
            c.drawColor(android.graphics.Color.WHITE)
            c.drawBitmap(scaled, padX.toFloat(), padY.toFloat(), null)
            report("pad-scale${(s * 100).toInt()}", canvas)
            canvas.recycle()
            scaled.recycle()
        }

        crop.recycle()
    }
}

/** Small helper so the harness can record that it ran. */
object DirectoryMarker {
    fun mark(dir: File, text: String) {
        runCatching { File(dir, "marker.txt").writeText(text) }
    }
}
