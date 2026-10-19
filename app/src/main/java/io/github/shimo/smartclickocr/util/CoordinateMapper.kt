package io.github.shimo.smartclickocr.util

import io.github.shimo.smartclickocr.model.ScreenMeta
import kotlin.math.roundToInt

/**
 * Single source of truth for screen coordinate conversion.
 *
 * Calibration (crosshair / selection box), "test click", real automation clicks and
 * OCR screenshot cropping all go through this object, so a preview never disagrees
 * with what actually gets tapped (spec §7.2.4/§7.2.5).
 *
 * Coordinate spaces involved:
 *  - **screen space**: pixels in the current display's rotated frame. `0,0` is the
 *    top-left of the *usable* display area; this is what `dispatchGesture` expects.
 *  - **capture space**: pixels of the MediaProjection bitmap. Full-screen captures use
 *    the same frame as screen space, but some devices/ColorOS versions letterbox the
 *    capture or rotate differently, hence [captureToScreen]/[screenToCapture].
 */
object CoordinateMapper {

    /**
     * Maps a touch/crosshair position inside an overlay window to screen pixels.
     *
     * An overlay added with `TYPE_APPLICATION_OVERLAY` and `Gravity.TOP|START` is laid out
     * inside the **usable** area, i.e. below the status bar. The values handed to
     * `dispatchGesture` and used for screenshot cropping are in the **full display**
     * frame, so the status-bar height must be added. Passing 0 here made every selection
     * land one status bar too high.
     */
    fun overlayToScreen(
        viewX: Float,
        viewY: Float,
        offsetX: Int = 0,
        offsetY: Int = systemBarTop()
    ): Pair<Float, Float> = Pair(viewX + offsetX, viewY + offsetY)

    /** Inverse of [overlayToScreen]. */
    fun screenToOverlay(
        screenX: Float,
        screenY: Float,
        offsetX: Int = 0,
        offsetY: Int = systemBarTop()
    ): Pair<Float, Float> = Pair(screenX - offsetX, screenY - offsetY)

    /** Height of the status bar in the current orientation; 0 when unknown. */
    fun systemBarTop(): Int = runCatching {
        io.github.shimo.smartclickocr.util.ScreenMetricsProvider.systemBarInsets().top
    }.getOrDefault(0)

    /**
     * Converts a screen-space point to capture-bitmap pixels, handling captures whose
     * size differs from the screen (letterboxing, differing rotation, scaled captures).
     */
    /**
     * Converts a screen-space point to capture-bitmap pixels.
     *
     * The capture bitmap may be shorter than the full display (system bars excluded).
     * The missing rows are NOT split evenly: MediaProjection returns the area below the
     * status bar but keeps the navigation bar area, so the whole difference sits at the
     * top. Treating it as a pure scale factor previously smeared coordinates, and
     * assuming a symmetric split put the crop one bar too high — which is exactly the
     * reported "recognised area sits slightly above the selected area" symptom.
     *
     * [topInset] is the offset to subtract before scaling; it defaults to whatever the
     * caller measured (see [captureTopInset]).
     */
    fun screenToCapture(
        screenX: Float,
        screenY: Float,
        screenMeta: ScreenMeta,
        captureWidth: Int,
        captureHeight: Int,
        topInset: Int = captureTopInset(screenMeta, captureWidth, captureHeight)
    ): Pair<Float, Float> {
        if (screenMeta.widthPx <= 0 || screenMeta.heightPx <= 0) return Pair(screenX, screenY)
        if (captureWidth <= 0 || captureHeight <= 0) return Pair(screenX, screenY)

        val contentW = screenMeta.widthPx.coerceAtLeast(1)
        val sx = captureWidth.toFloat() / contentW
        val sy = captureHeight.toFloat() / (screenMeta.heightPx - topInset).coerceAtLeast(1)
        return Pair(screenX * sx, (screenY - topInset).coerceAtLeast(0f) * sy)
    }

    /**
     * Rows missing from the top of the capture.
     *
     * The screenshot is shorter than the display by exactly the height that the window
     * manager excludes from the top. We use that difference directly rather than the
     * status-bar inset, because some devices report bar sizes that do not add up to the
     * actual crop.
     */
    /**
     * Rows missing from the top of the capture.
     *
     * The app creates its virtual display at the full display size, so in production the
     * capture differs from the display only by the rows the system omits (26 px on a
     * 2400 px display). A capture that is a clean fraction of the display is a scaled
     * capture instead — there the whole frame maps proportionally and nothing is cut off,
     * so the inset must be 0 or the image would be shifted.
     */
    fun captureTopInset(screenMeta: ScreenMeta, captureWidth: Int, captureHeight: Int): Int {
        if (!screenMeta.isValid || captureHeight <= 0 || captureWidth <= 0) return 0
        // Pure scale (e.g. half resolution): no cropping happened.
        val widthMatches = captureWidth == screenMeta.widthPx
        if (!widthMatches) return 0
        val missing = screenMeta.heightPx - captureHeight
        if (missing <= 0) return 0
        // A tiny difference is the system bars being excluded; a large one means the frame
        // was resized rather than cropped.
        return if (missing * 10 < screenMeta.heightPx) missing else 0
    }

    /**
     * Converts a region expressed in screen pixels into capture-bitmap pixels,
     * clamped to the bitmap. Returns null when the result is empty.
     */
    fun regionScreenToCapture(
        left: Int, top: Int, right: Int, bottom: Int,
        screenMeta: ScreenMeta,
        captureWidth: Int,
        captureHeight: Int,
        topInset: Int = captureTopInset(screenMeta, captureWidth, captureHeight)
    ): IntArray? {
        val inset = topInset.coerceAtLeast(0)
        val (l, t) = screenToCapture(left.toFloat(), top.toFloat(), screenMeta, captureWidth, captureHeight, inset)
        val (r, b) = screenToCapture(right.toFloat(), bottom.toFloat(), screenMeta, captureWidth, captureHeight, inset)
        val cl = l.roundToInt().coerceIn(0, captureWidth)
        val ct = t.roundToInt().coerceIn(0, captureHeight)
        val cr = r.roundToInt().coerceIn(0, captureWidth)
        val cb = b.roundToInt().coerceIn(0, captureHeight)
        if (cr <= cl || cb <= ct) return null
        return intArrayOf(cl, ct, cr, cb)
    }

    /** Clamps a point so a tap still lands inside the display. */
    fun clampToScreen(x: Float, y: Float, screenMeta: ScreenMeta): Pair<Float, Float> {
        if (!screenMeta.isValid) return Pair(x, y)
        val cx = x.coerceIn(0f, (screenMeta.widthPx - 1).toFloat())
        val cy = y.coerceIn(0f, (screenMeta.heightPx - 1).toFloat())
        return Pair(cx, cy)
    }

    /**
     * Whether a saved, calibrated point is still usable on the current display.
     * Different width/height/rotation means the UI has probably moved, so the user
     * should re-calibrate (spec §7.2.5).
     */
    fun needsRecalibration(saved: ScreenMeta, current: ScreenMeta): Boolean =
        saved.isValid && saved.needsRecalibration(current)

    fun describeRecalibration(saved: ScreenMeta, current: ScreenMeta): String =
        "屏幕已变化（保存时 ${saved.widthPx}×${saved.heightPx} 旋转${saved.rotation * 90}°，" +
            "当前 ${current.widthPx}×${current.heightPx} 旋转${current.rotation * 90}°），建议重新校准位置"
}
