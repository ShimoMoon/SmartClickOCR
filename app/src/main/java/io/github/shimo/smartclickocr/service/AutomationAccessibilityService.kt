package io.github.shimo.smartclickocr.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import io.github.shimo.smartclickocr.model.AutomationAction
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Registered in Android settings by the user; provides real taps, swipes and Back.
 *
 * All gestures go through [dispatchGesture], the only supported API for injecting
 * touches without root. The service never tries to enable itself.
 */
class AutomationAccessibilityService : AccessibilityService() {

    /** Weak-ish singleton reference so the engine can reach the active service. */
    companion object {
        private const val TAG = "AutomationA11y"
        private const val GESTURE_TIMEOUT_MS = 10_000L

        @Volatile
        var instance: AutomationAccessibilityService? = null
            private set

        fun isConnected(): Boolean = instance != null

        /** Attempts a gesture; returns an error message or null on success. */
        suspend fun performGesture(gesture: GestureDescription): String? {
            val service = instance ?: return "无障碍服务未启用，无法派发手势"
            return service.dispatchAndAwait(gesture)
        }

        /** Performs Android's global Back action. */
        fun performBack(): String? {
            val service = instance ?: return "无障碍服务未启用，无法执行返回"
            return if (service.performGlobalAction(GLOBAL_ACTION_BACK)) null else "返回动作被系统拒绝"
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No event listening is needed for this app.
    }

    /**
     * Dispatches [gesture] and suspends until the system reports completion,
     * cancellation or timeout, so the engine keeps actions strictly serial.
     */
    private suspend fun dispatchAndAwait(gesture: GestureDescription): String? =
        suspendCancellableCoroutine { cont ->
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) {
                        cont.resume("手势被系统取消（可能被其他手势打断或被系统限制）")
                    }
                }
            }
            val accepted = try {
                dispatchGesture(gesture, callback, null)
            } catch (e: Exception) {
                if (cont.isActive) cont.resume("手势派发异常：${e.message}")
                return@suspendCancellableCoroutine
            }
            if (!accepted) {
                if (cont.isActive) cont.resume("手势派发失败（系统拒绝了该手势）")
                return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation { /* The system gesture cannot be withdrawn. */ }
        }

    /** Builds a single-tap gesture at [x],[y]. */
    fun buildTap(x: Float, y: Float, durationMs: Long = 50L): GestureDescription {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(1L))
        return GestureDescription.Builder().addStroke(stroke).build()
    }

    /** Builds a swipe gesture from ([x],[y]) to ([endX],[endY]). */
    fun buildSwipe(
        x: Float, y: Float, endX: Float, endY: Float, durationMs: Long
    ): GestureDescription {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(1L))
        return GestureDescription.Builder().addStroke(stroke).build()
    }

    /**
     * Executes one gesture-based action and reports success/failure.
     * BACK is handled synchronously via [performBack].
     */
    suspend fun executeGestureAction(action: AutomationAction): String? {
        return when (action.type) {
            io.github.shimo.smartclickocr.model.ActionType.CLICK ->
                dispatchAndAwait(buildTap(action.x, action.y))
            io.github.shimo.smartclickocr.model.ActionType.SWIPE ->
                dispatchAndAwait(
                    buildSwipe(action.x, action.y, action.endX, action.endY, action.durationMs)
                )
            io.github.shimo.smartclickocr.model.ActionType.BACK -> performBack()
            else -> null
        }
    }

    /** One-shot tap used by "test click". */
    suspend fun testTap(x: Float, y: Float): String? = dispatchAndAwait(buildTap(x, y))

    /** One-shot swipe used by "test swipe". */
    suspend fun testSwipe(x: Float, y: Float, endX: Float, endY: Float, durationMs: Long): String? =
        dispatchAndAwait(buildSwipe(x, y, endX, endY, durationMs))
}
