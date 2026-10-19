package io.github.shimo.smartclickocr.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import io.github.shimo.smartclickocr.model.ScreenMeta
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Owns the MediaProjection session and produces single screenshots on demand.
 *
 * Screenshots are never written to disk: they live in memory only and are recycled
 * as soon as the caller is done (spec §8.1). Any failure (no session, revoked
 * permission, protected content) is reported as an exception so the engine can
 * stop with an error instead of silently mis-matching OCR.
 *
 * A persistent [ImageReader.OnImageAvailableListener] is registered for the whole
 * session. MediaProjection only emits a frame when the screen changes, so arming a
 * listener per capture would time out whenever the app being watched is idle.
 */
class ScreenCaptureManager(private val context: Context) {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile
    private var latestImage: Image? = null
    private val imageLock = Object()

    /**
     * Signalled whenever a new frame is buffered. Re-created for every capture: a
     * one-shot latch would stay open after the first frame and later captures would
     * block until their timeout instead of returning immediately.
     */
    private var frameArrived = CountDownLatch(1)

    /**
     * True once the display has composed at least once in this session. Frames buffered
     * before that can be blank, so they must not be handed to OCR.
     */
    private var hasFreshFrame = false

    private var sessionMeta = ScreenMeta.EMPTY

    val isActive: Boolean get() = projection != null

    /** Geometry of the active session (full display size + rotation at start). */
    val sessionScreenMeta: ScreenMeta get() = sessionMeta

    /**
     * Starts (or restarts) a capture session from the result of the system's
     * permission dialog. [width]/[height] must be the **full** display size.
     *
     * Android 14+ enforces two extra rules that must be respected here:
     *  - `registerCallback()` must be called **before** `createVirtualDisplay()`;
     *  - a consent `Intent` can only be used once, so the caller must request a fresh
     *    consent through the system dialog for every new session.
     */
    @Synchronized
    fun start(
        resultCode: Int,
        data: android.content.Intent,
        width: Int,
        height: Int,
        densityDpi: Int,
        sessionRotation: Int = 0
    ) {
        stop()
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = try {
            manager.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            throw CaptureException(
                "无法创建屏幕捕获会话：${e.message}。请重新点击「屏幕截图」授权。", e
            )
        } ?: throw CaptureException("无法创建屏幕捕获会话：系统未返回有效授权，请重新授权")

        val captureW = if (width > 0) width else 1080
        val captureH = if (height > 0) height else 2400
        val captureDpi = if (densityDpi > 0) densityDpi else 320
        sessionMeta = ScreenMeta(captureW, captureH, sessionRotation)

        val ht = HandlerThread("smartclickocr-capture").also { it.start() }
        thread = ht
        val h = Handler(ht.looper)
        handler = h

        // 1) Callback FIRST — required since Android 14, otherwise createVirtualDisplay
        //    throws IllegalStateException.
        try {
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection stopped by the system")
                    stop()
                }
            }, h)
        } catch (e: Exception) {
            // Older paths may reject a second registration; a missing callback only
            // costs us the automatic onStop cleanup.
            Log.w(TAG, "registerCallback failed", e)
        }

        // 2) Then the reader and the virtual display.
        val reader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 3)
        // Keep the newest frame available at all times.
        reader.setOnImageAvailableListener({ r ->
            try {
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                synchronized(imageLock) {
                    // Close the previous frame so native buffers never accumulate. The
                    // buffered image is always one the caller has NOT taken ownership of,
                    // because awaitImage() nulls it out when handing it over.
                    latestImage?.let { runCatching { it.close() } }
                    latestImage = img
                    // The display has composed at least once, so future frames are real.
                    hasFreshFrame = true
                    frameArrived.countDown()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to acquire frame", e)
            }
        }, h)
        imageReader = reader

        val vd = try {
            proj.createVirtualDisplay(
                "smartclickocr-display",
                captureW,
                captureH,
                captureDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                h
            )
        } catch (e: Exception) {
            // Never leave a half-built session behind.
            runCatching { reader.setOnImageAvailableListener(null, null) }
            runCatching { reader.close() }
            imageReader = null
            runCatching { ht.quitSafely() }
            thread = null
            handler = null
            throw CaptureException("创建虚拟屏幕失败：${e.message}", e)
        }
        virtualDisplay = vd
        projection = proj
    }

    /**
     * Grabs a frame as an ARGB bitmap of the session size.
     * The caller owns and must recycle the returned bitmap.
     *
     * Retries a few times when the frame comes back blank: a MediaProjection display
     * that has not drawn yet (or was just re-created after the consent dialog) can hand
     * back an empty frame, which used to surface as "text is on screen but nothing is
     * detected". Blocks, so always call it off the main thread.
     */
    @Synchronized
    fun captureBitmap(timeoutMs: Long = 4000L, attempts: Int = 3): Bitmap {
        if (imageReader == null || projection == null) {
            throw CaptureException("截图会话未启动或已失效，请重新授予屏幕捕获权限")
        }
        var last: Bitmap? = null
        for (attempt in 1..attempts) {
            val image = awaitImage(timeoutMs)
            val bitmap = try {
                imageToBitmap(image)
            } finally {
                runCatching { image.close() }
            }
            if (!looksBlank(bitmap)) {
                last?.recycle()
                return bitmap
            }
            // Discard the blank frame and wait for the display to draw real content.
            last?.recycle()
            last = bitmap
            if (attempt < attempts) {
                // Nudge the compositor and give it a moment before trying again.
                requestFrameRefresh()
                Thread.sleep(250L)
            }
        }
        // Every attempt was flat: report it instead of letting OCR silently find nothing.
        val size = last?.let { "${it.width}×${it.height}" } ?: "?"
        last?.recycle()
        throw CaptureException(
            "截图内容为空白（${size}）：屏幕可能处于受保护状态，或系统未授权向本应用共享画面。" +
                "请重新授予屏幕截图权限后重试"
        )
    }

    /**
     * Waits for a frame and **transfers ownership** of it to the caller.
     *
     * Strategy, in order:
     * 1. a buffered frame that was produced AFTER this session actually composed once
     *    (a frame captured while the virtual display was still being created can be
     *    blank, which made OCR report "no text" on a screen that clearly had some);
     * 2. a newly composed frame, requested by nudging the display;
     * 3. the buffered frame anyway, if the screen is static and no new frame arrives —
     *    a stale-but-real frame is still far more useful than a timeout error.
     *
     * Taking the image out of [latestImage] under the lock is essential: the listener
     * closes the previous frame when a newer one arrives, which would otherwise close
     * the very image the caller is still converting ("Image is already closed").
     */
    private fun awaitImage(timeoutMs: Long): Image {
        // 1) Prefer a frame known to be real (i.e. not the initialisation blank frame).
        synchronized(imageLock) {
            latestImage?.takeIf { hasFreshFrame }?.let {
                latestImage = null
                return it
            }
        }

        // 2) Ask the compositor for a new frame and wait for it.
        val latch: CountDownLatch
        synchronized(imageLock) {
            frameArrived = CountDownLatch(1)
            latch = frameArrived
        }
        requestFrameRefresh()
        if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            synchronized(imageLock) {
                latestImage?.let {
                    latestImage = null
                    return it
                }
            }
        }

        // 3) A completely static screen may never compose again. Fall back to whatever
        //    is buffered rather than failing the OCR action.
        synchronized(imageLock) {
            latestImage?.let {
                latestImage = null
                return it
            }
        }

        throw CaptureException(
            "截图失败：没有可用的画面（屏幕可能处于受保护状态或被遮挡），请重新授予截图权限后重试"
        )
    }

    /**
     * Nudges the display to emit a fresh frame.
     *
     * MediaProjection mirrors the display, so an idle screen produces no new frames.
     * Re-attaching the reader's surface is the reliable way to force one composition;
     * if that does nothing the caller simply waits out its timeout and reports it.
     */
    private fun requestFrameRefresh() {
        runCatching {
            val vd = virtualDisplay ?: return
            val surface = imageReader?.surface ?: return
            // Re-attaching forces the compositor to emit a frame even when the screen is
            // completely static, which is the normal case for a menu or a game lobby.
            // (ImageReader.surface is read-only, so only the display is re-bound.)
            vd.surface = surface
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes.firstOrNull() ?: throw CaptureException("截图数据为空")
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + if (pixelStride > 0) rowPadding / pixelStride else 0

        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)

        return if (paddedWidth != image.width) {
            val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            padded.recycle()
            cropped
        } else {
            padded
        }
    }

    /** True when a bitmap is essentially one flat colour, i.e. no screen content. */
    fun looksBlank(bitmap: Bitmap, sampleStep: Int = 24): Boolean {
        if (bitmap.width <= 0 || bitmap.height <= 0) return true
        var min = Int.MAX_VALUE
        var max = Int.MIN_VALUE
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val c = bitmap.getPixel(x, y)
                val lum = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
                if (lum < min) min = lum
                if (lum > max) max = lum
                x += sampleStep
            }
            y += sampleStep
        }
        return (max - min) < 8
    }

    /** Releases the session and every native resource. */
    @Synchronized
    fun stop() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { imageReader?.close() }
        imageReader = null
        synchronized(imageLock) {
            latestImage?.let { runCatching { it.close() } }
            latestImage = null
        }
        runCatching { projection?.stop() }
        projection = null
        runCatching { thread?.quitSafely() }
        thread = null
        handler = null
        sessionMeta = ScreenMeta.EMPTY
        hasFreshFrame = false
    }

    class CaptureException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private companion object {
        const val TAG = "ScreenCapture"
    }
}
