package io.github.shimo.smartclickocr.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.github.shimo.smartclickocr.service.AutomationService
import io.github.shimo.smartclickocr.overlay.OverlayController
import io.github.shimo.smartclickocr.util.ScreenMetricsProvider

/**
 * Transparent Activity launched while the user is on the target app.
 *
 * It does not draw a UI of its own: it asks the running [AutomationService] to show
 * either the draggable crosshair or the region box. When the user confirms, the
 * result is delivered through a static hand-off back to the editor.
 *
 * Confirm intentionally does NOT dispatch a tap — calibration must never trigger the
 * app underneath (spec §7.2.1 step 4).
 */
class CalibrationActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_POINT = "point"
        const val MODE_REGION = "region"

        /** Filled in before launching the editor again. */
        @Volatile var lastPoint: Pair<Float, Float>? = null
        @Volatile var lastRegion: IntArray? = null
        @Volatile var cancelled: Boolean = false

        fun startPoint(context: Context) {
            lastPoint = null
            lastRegion = null
            cancelled = false
            context.startActivity(
                Intent(context, CalibrationActivity::class.java)
                    .putExtra(EXTRA_MODE, MODE_POINT)
            )
        }

        fun startRegion(context: Context) {
            lastPoint = null
            lastRegion = null
            cancelled = false
            context.startActivity(
                Intent(context, CalibrationActivity::class.java)
                    .putExtra(EXTRA_MODE, MODE_REGION)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScreenMetricsProvider.attach(this)

        val service = AutomationService.instance
        if (service == null) {
            Toast.makeText(this, "请先授予屏幕截图/悬浮窗权限并回到应用主界面准备", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_POINT
        // Leave the calibration overlays to the service so they survive rotation and
        // keep working while this Activity finishes.
        service.showOverlay(
            if (mode == MODE_REGION) OverlayController.Mode.PICK_REGION
            else OverlayController.Mode.PICK_POINT
        )
        finish()
    }
}
