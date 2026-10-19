package io.github.shimo.smartclickocr.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import io.github.shimo.smartclickocr.model.ActionResult
import io.github.shimo.smartclickocr.model.ScreenMeta
import io.github.shimo.smartclickocr.util.CoordinateMapper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Crops a captured frame to the configured region and runs on-device Chinese OCR.
 *
 * The recogniser ships inside the APK (bundled model), so it does not need network
 * access at runtime.
 *
 * Two things make the difference between "text is on screen but nothing is detected"
 * and a reliable result:
 *  - the crop gets a **background-coloured border**, because a glyph touching the crop
 *    edge is otherwise treated as an icon/decoration and dropped;
 *  - the crop is **rescaled** into the size range the detector handles best.
 */
class OcrManager {

    /** Result of recognising one region. */
    data class Recognition(
        val text: String,
        val blocks: List<OcrMatcher.TextBlock>,
        val regionInCapture: IntArray
    )

    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /**
     * Recognises the region of [fullFrame].
     *
     * Region coordinates are in **screen space**; they are converted with the same
     * [CoordinateMapper] used for taps so a preview always agrees with reality.
     *
     * @throws ScreenCaptureManager.CaptureException on any real failure.
     */
    suspend fun recognizeRegion(
        fullFrame: Bitmap,
        screenMeta: ScreenMeta,
        left: Int, top: Int, right: Int, bottom: Int
    ): Recognition {
        val region = CoordinateMapper.regionScreenToCapture(
            left, top, right, bottom, screenMeta, fullFrame.width, fullFrame.height
        ) ?: throw ScreenCaptureManager.CaptureException("识别区域无效（宽或高为 0，或超出截图范围）")

        val validation = OcrMatcher.validateRegion(
            region[0], region[1], region[2], region[3], fullFrame.width, fullFrame.height
        )
        if (validation != null) throw ScreenCaptureManager.CaptureException(validation)

        val cropWidth = region[2] - region[0]
        val cropHeight = region[3] - region[1]

        // Bitmap.createBitmap can return the SAME instance when the region covers the
        // whole frame; only recycle what we actually allocated.
        val cropped = try {
            Bitmap.createBitmap(fullFrame, region[0], region[1], cropWidth, cropHeight)
        } catch (e: Exception) {
            throw ScreenCaptureManager.CaptureException("裁剪识别区域失败：${e.message}", e)
        }
        val ownsCrop = cropped !== fullFrame

        // A single large glyph often touches a crop edge; ML Kit then treats it as an
        // icon instead of text. This padding lives INSIDE the bitmap (it does not widen
        // the selected screen area), so it cannot pull neighbouring labels in.
        val padded = addOcrPadding(cropped)
        val ownsPadding = padded !== cropped

        // Bring the padded crop into the size range the detector likes best.
        val forOcr = rescaleForOcr(padded)
        val ownsScaled = forOcr !== padded && forOcr !== cropped && forOcr !== fullFrame

        try {
            val image = InputImage.fromBitmap(forOcr, 0)
            val text = awaitRecognize(image)

            // Bounding boxes refer to the (padded, possibly scaled) OCR bitmap; map them
            // back to capture pixels so callers always see real screen coordinates.
            val paddingX = (padded.width - cropWidth) / 2
            val paddingY = (padded.height - cropHeight) / 2
            val scaleX = if (forOcr.width > 0) padded.width.toFloat() / forOcr.width else 1f
            val scaleY = if (forOcr.height > 0) padded.height.toFloat() / forOcr.height else 1f

            // Collect at LINE level, falling back to BLOCK level when a block exposes no
            // lines. Relying on `block.lines` alone returns an empty string whenever the
            // engine groups text differently, which surfaced as "there is text but it is
            // not detected".
            val blocks = ArrayList<OcrMatcher.TextBlock>()
            for (block in text.textBlocks) {
                val lines = block.lines
                if (lines.isNotEmpty()) {
                    for (line in lines) {
                        toBlock(line.text, line.boundingBox, scaleX, scaleY, paddingX, paddingY, region)
                            ?.let(blocks::add)
                    }
                } else {
                    toBlock(block.text, block.boundingBox, scaleX, scaleY, paddingX, paddingY, region)
                        ?.let(blocks::add)
                }
            }

            // Last resort: if nothing was collected but the raw text is non-empty, use it
            // verbatim so a recognised string is never thrown away.
            if (blocks.isEmpty()) {
                val raw = text.text ?: ""
                return Recognition(raw.trim(), emptyList(), region)
            }
            return Recognition(OcrMatcher.joinInReadingOrder(blocks), blocks, region)
        } finally {
            // ML Kit has consumed these bitmaps by the time awaitRecognize returns, so
            // all of them can be freed now; the caller frees the full frame.
            if (ownsScaled) runCatching { forOcr.recycle() }
            if (ownsPadding) runCatching { padded.recycle() }
            if (ownsCrop) runCatching { cropped.recycle() }
        }
    }

    /** Converts one recognised fragment into capture pixels, undoing padding and scaling. */
    private fun toBlock(
        text: String,
        box: android.graphics.Rect?,
        scaleX: Float,
        scaleY: Float,
        paddingX: Int,
        paddingY: Int,
        region: IntArray
    ): OcrMatcher.TextBlock? {
        if (text.isBlank()) return null
        fun mapX(v: Int) = ((v * scaleX).toInt() - paddingX) + region[0]
        fun mapY(v: Int) = ((v * scaleY).toInt() - paddingY) + region[1]
        return OcrMatcher.TextBlock(
            text = text,
            left = mapX(box?.left ?: 0),
            top = mapY(box?.top ?: 0),
            right = mapX(box?.right ?: 0),
            bottom = mapY(box?.bottom ?: 0)
        )
    }

    /**
     * Surrounds the crop with a border painted in the dominant background colour.
     *
     * The colour is sampled from the four corners so the border blends with a light or
     * dark theme instead of introducing a hard edge that confuses line detection.
     * Returns the original bitmap when padding is unnecessary or allocation fails.
     */
    private fun addOcrPadding(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        if (w <= 0 || h <= 0) return source
        val padding = (maxOf(w, h) / 8).coerceIn(MIN_PADDING_PX, MAX_PADDING_PX)
        return try {
            val samples = intArrayOf(
                source.getPixel(0, 0), source.getPixel(w - 1, 0),
                source.getPixel(0, h - 1), source.getPixel(w - 1, h - 1)
            )
            val averageBrightness = samples.map { color ->
                (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000
            }.average()
            val background = if (averageBrightness < 128) Color.BLACK else Color.WHITE

            val padded = Bitmap.createBitmap(w + padding * 2, h + padding * 2, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(padded)
            canvas.drawColor(background)
            canvas.drawBitmap(source, padding.toFloat(), padding.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
            padded
        } catch (e: Exception) {
            source
        }
    }

    /**
     * Scales the crop into the range ML Kit recognises best.
     *
     *  - Small crop (short side < [MIN_OCR_SHORT_SIDE]) is enlarged, so thin/small glyphs
     *    stop being misread.
     *  - Large crop (long side > [MAX_OCR_DIMENSION]) is shrunk to bound memory.
     *  - Anything in between is passed through untouched.
     * Returns the original bitmap when no scaling is needed or scaling fails.
     */
    private fun rescaleForOcr(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        if (w <= 0 || h <= 0) return source

        val longSide = maxOf(w, h)
        val shortSide = minOf(w, h)

        val factor = when {
            shortSide < MIN_OCR_SHORT_SIDE -> (MIN_OCR_SHORT_SIDE.toFloat() / shortSide)
                .coerceAtMost(MAX_UPSCALE)
            longSide > MAX_OCR_DIMENSION -> MAX_OCR_DIMENSION.toFloat() / longSide
            else -> 1f
        }
        if (factor == 1f) return source

        val nw = (w * factor).toInt().coerceAtLeast(1)
        val nh = (h * factor).toInt().coerceAtLeast(1)
        return try {
            Bitmap.createScaledBitmap(source, nw, nh, true)
        } catch (e: Exception) {
            source
        }
    }

    /**
     * Full OCR_CHECK evaluation: capture is done by the caller, matching happens here.
     * "Not found" is [ActionResult.Continue]; only real failures throw.
     */
    suspend fun evaluate(
        fullFrame: Bitmap,
        screenMeta: ScreenMeta,
        left: Int, top: Int, right: Int, bottom: Int,
        targetText: String
    ): Pair<ActionResult, String> {
        val recognition = recognizeRegion(fullFrame, screenMeta, left, top, right, bottom)
        // Match against the whole text AND against each block, so a short target that the
        // detector grouped next to other lines still counts as found.
        val result = OcrMatcher.evaluateBlocks(recognition.blocks, recognition.text, targetText)
        return Pair(result, recognition.text)
    }

    private suspend fun awaitRecognize(image: InputImage) = suspendCancellableCoroutine { cont ->
        recognizer.process(image)
            .addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
            .addOnFailureListener { e ->
                if (cont.isActive) {
                    cont.resumeWith(
                        Result.failure(
                            ScreenCaptureManager.CaptureException("OCR 识别失败：${e.message}", e)
                        )
                    )
                }
            }
    }

    fun close() {
        runCatching { recognizer.close() }
    }

    private companion object {
        /** Longest side handed to ML Kit; larger regions are shrunk to bound memory. */
        const val MAX_OCR_DIMENSION = 2400

        /** Below this short side the crop is enlarged so small glyphs stay legible. */
        const val MIN_OCR_SHORT_SIDE = 640

        /** Never enlarge more than this, or artefacts start hurting recognition. */
        const val MAX_UPSCALE = 6f

        /** Border added around every crop so edge-touching glyphs stay valid text. */
        const val MIN_PADDING_PX = 24
        const val MAX_PADDING_PX = 160
    }
}