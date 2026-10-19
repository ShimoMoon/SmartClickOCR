package io.github.shimo.smartclickocr.util

import android.content.Context
import android.content.res.Resources
import android.graphics.Insets
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowInsets
import android.view.WindowManager
import io.github.shimo.smartclickocr.model.ScreenMeta

/**
 * Reports the display geometry used for calibration and for gesture dispatch.
 *
 * Uses the *real* display size (not the app window) and the current rotation, so
 * crosshair selection, OCR cropping and taps all agree even with a status bar,
 * gesture navigation bar or ColorOS display scaling in the way.
 */
object ScreenMetricsProvider {

    /** Refreshed by the application/service whenever it starts. */
    @Volatile
    private var applicationContext: Context? = null

    fun attach(context: Context) {
        applicationContext = context.applicationContext
    }

    /**
     * Geometry used for **calibration and coordinates**.
     *
     * This must be the FULL display size, not the usable area: `dispatchGesture` and a
     * hand-typed coordinate are both expressed in the full-screen frame whose origin is
     * (0,0) at the very top-left, status bar included. Reporting the usable height
     * instead made the calibration map and the typed-coordinate hint disagree with the
     * real tap position by the height of the system bars.
     */
    fun current(): ScreenMeta = realDisplaySize()

    /**
     * The **full** physical display size, in the current orientation.
     *
     * Also what the screenshot bitmap must match: MediaProjection decimates or clips a
     * virtual display that is smaller than the real display, which would break the 1:1
     * mapping between screen coordinates and screenshot pixels.
     */
    fun realDisplaySize(): ScreenMeta {
        val ctx = applicationContext ?: return ScreenMeta.EMPTY
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return ScreenMeta.EMPTY
        val rotation = when (wm.defaultDisplay.rotation) {
            1 -> 1
            2 -> 2
            3 -> 3
            else -> 0
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // maximumWindowMetrics.bounds is the full display, insets included.
            val bounds = wm.maximumWindowMetrics.bounds
            return ScreenMeta(bounds.width(), bounds.height(), rotation)
        }
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return ScreenMeta(dm.widthPixels, dm.heightPixels, rotation)
    }

    fun densityDpi(): Int {
        val ctx = applicationContext ?: return Resources.getSystem().displayMetrics.densityDpi
        return ctx.resources.displayMetrics.densityDpi
    }

    /** Insets of the status bar / navigation bar for the current orientation. */
    fun systemBarInsets(): Insets {
        val ctx = applicationContext ?: return Insets.NONE
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return Insets.NONE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.maximumWindowMetrics
            metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
        } else {
            Insets.NONE
        }
    }
}
