package io.github.shimo.smartclickocr.engine

import android.graphics.Bitmap
import android.util.Log
import io.github.shimo.smartclickocr.model.ActionResult
import io.github.shimo.smartclickocr.model.ActionType
import io.github.shimo.smartclickocr.model.AutomationAction
import io.github.shimo.smartclickocr.ocr.OcrManager
import io.github.shimo.smartclickocr.ocr.ScreenCaptureManager
import io.github.shimo.smartclickocr.service.AutomationAccessibilityService
import io.github.shimo.smartclickocr.util.ScreenMetricsProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Real executor: taps/swipes/back through the accessibility service, OCR through
 * MediaProjection + ML Kit.
 *
 * It reports only genuine failures as [ActionResult.Error]. "Text not found" is a
 * normal [ActionResult.Continue] (spec §4.2).
 */
class DeviceActionExecutor(
    private val capture: ScreenCaptureManager,
    private val ocr: OcrManager,
    private val onTapFeedback: (Float, Float) -> Unit = { _, _ -> },
    private val onOcrDebug: (String) -> Unit = {}
) : ActionExecutor {

    override suspend fun execute(action: AutomationAction): ActionResult {
        return when (action.type) {
            ActionType.CLICK -> performClick(action.x, action.y)
            ActionType.SWIPE -> performSwipe(action)
            ActionType.BACK -> {
                val error = AutomationAccessibilityService.performBack()
                if (error != null) ActionResult.Error(error) else ActionResult.Continue
            }
            ActionType.WAIT -> {
                delay(action.waitDurationMs)
                ActionResult.Continue
            }
            // STOP_IF uses exactly the same screen-reading path as OCR_CHECK; the engine
            // turns a match into a stop for both.
            ActionType.OCR_CHECK, ActionType.STOP_IF -> performOcrCheck(action)
        }
    }

    private suspend fun performClick(x: Float, y: Float): ActionResult {
        val service = AutomationAccessibilityService.instance
            ?: return ActionResult.Error("无障碍服务未启用，无法点击。请在设置中开启 SmartClickOCR 的无障碍服务")
        val error = service.testTap(x, y)
        if (error != null) return ActionResult.Error(error)
        runCatching { onTapFeedback(x, y) }
        return ActionResult.Continue
    }

    private suspend fun performSwipe(action: AutomationAction): ActionResult {
        val service = AutomationAccessibilityService.instance
            ?: return ActionResult.Error("无障碍服务未启用，无法滑动。请在设置中开启 SmartClickOCR 的无障碍服务")
        if (action.durationMs <= 0) return ActionResult.Error("滑动时长必须大于 0")
        val error = service.testSwipe(action.x, action.y, action.endX, action.endY, action.durationMs)
        if (error != null) return ActionResult.Error(error)
        return ActionResult.Continue
    }

    private suspend fun performOcrCheck(action: AutomationAction): ActionResult {
        if (!capture.isActive) {
            return ActionResult.Error("屏幕捕获会话未启动，无法进行 OCR。请回到主界面重新授予截图权限")
        }
        // Crop against the geometry the session was created with; using the live
        // metrics would misalign the region if the device rotated after starting.
        val sessionMeta = capture.sessionScreenMeta
        val metrics = if (sessionMeta.isValid) sessionMeta else ScreenMetricsProvider.realDisplaySize()
        var frame: Bitmap? = null
        return try {
            // captureBitmap blocks until the system delivers a frame, so keep it off
            // the main thread (the engine runs on Dispatchers.Main).
            frame = withContext(Dispatchers.Default) { capture.captureBitmap() }
            val (result, text) = ocr.evaluate(
                fullFrame = frame,
                screenMeta = metrics,
                left = action.regionLeft,
                top = action.regionTop,
                right = action.regionRight,
                bottom = action.regionBottom,
                targetText = action.targetText
            )
            runCatching {
                onOcrDebug("最近识别：\"${text.trim().replace("\n", " ⏎ ")}\" -> ${if (result == ActionResult.StopMatched) "匹配" else "不匹配"}")
            }
            result
        } catch (e: ScreenCaptureManager.CaptureException) {
            ActionResult.Error(e.message ?: "OCR 处理失败", e)
        } catch (e: Exception) {
            Log.e("DeviceActionExecutor", "OCR failed", e)
            ActionResult.Error("OCR 处理异常：${e.message}", e)
        } finally {
            runCatching { frame?.recycle() }
        }
    }
}
