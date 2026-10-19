package io.github.shimo.smartclickocr.model

import java.util.UUID

/**
 * Types of actions supported by the automation engine.
 *
 * [OCR_CHECK] and [STOP_IF] behave identically (both stop the task on an exact text
 * match); they are separate types only so the action list can say what it means:
 * "检测文字" versus "满足条件就停止".
 */
enum class ActionType {
    CLICK,
    BACK,
    WAIT,
    SWIPE,
    OCR_CHECK,
    STOP_IF;

    /** True when this action reads the screen and can end the task on a match. */
    val isOcrBased: Boolean get() = this == OCR_CHECK || this == STOP_IF

    val displayName: String
        get() = when (this) {
            CLICK -> "点击"
            BACK -> "返回"
            WAIT -> "等待"
            SWIPE -> "滑动"
            OCR_CHECK -> "OCR 检测"
            STOP_IF -> "条件停止"
        }

    companion object {
        fun fromName(value: String?): ActionType? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

/**
 * Screen metadata captured when a point/region is calibrated. Used to detect
 * orientation or resolution changes that would invalidate saved coordinates.
 */
data class ScreenMeta(
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val rotation: Int = 0
) {
    val isValid: Boolean get() = widthPx > 0 && heightPx > 0

    /** True when the current screen no longer matches what was calibrated. */
    fun needsRecalibration(current: ScreenMeta): Boolean {
        if (!isValid || !current.isValid) return false
        return widthPx != current.widthPx || heightPx != current.heightPx || rotation != current.rotation
    }

    companion object {
        val EMPTY = ScreenMeta()
    }
}

/**
 * A single automation action. One data class covers all action types; fields that
 * do not apply to the current [type] are ignored by the engine and are not
 * persisted meaningfully (see [normalized]).
 */
data class AutomationAction(
    val id: String = UUID.randomUUID().toString(),
    val type: ActionType,
    /** Delay waited *before* this action starts. Applies to the very first action too. */
    val delayBeforeMs: Long = 1000L,
    val enabled: Boolean = true,

    // CLICK
    val x: Float = -1f,
    val y: Float = -1f,

    // SWIPE
    val endX: Float = -1f,
    val endY: Float = -1f,
    val durationMs: Long = 300L,

    // WAIT
    val waitDurationMs: Long = 1000L,

    // OCR_CHECK
    val targetText: String = "",
    val regionLeft: Int = 0,
    val regionTop: Int = 0,
    val regionRight: Int = 0,
    val regionBottom: Int = 0,

    /** Screen size/rotation at calibration time; empty when not applicable. */
    val screenMeta: ScreenMeta = ScreenMeta.EMPTY
) {
    val isClickConfigured: Boolean get() = x >= 0f && y >= 0f
    val isSwipeConfigured: Boolean get() = x >= 0f && y >= 0f && endX >= 0f && endY >= 0f
    val isRegionConfigured: Boolean
        get() = regionRight > regionLeft && regionBottom > regionTop

    val regionWidth: Int get() = (regionRight - regionLeft).coerceAtLeast(0)
    val regionHeight: Int get() = (regionBottom - regionTop).coerceAtLeast(0)

    /** Drops parameters that are meaningless for this action type. */
    fun normalized(): AutomationAction = when (type) {
        ActionType.CLICK -> copy(
            endX = -1f, endY = -1f, durationMs = 300L, waitDurationMs = 1000L,
            targetText = "", regionLeft = 0, regionTop = 0, regionRight = 0, regionBottom = 0
        )
        ActionType.BACK -> copy(
            x = -1f, y = -1f, endX = -1f, endY = -1f, durationMs = 300L, waitDurationMs = 1000L,
            targetText = "", regionLeft = 0, regionTop = 0, regionRight = 0, regionBottom = 0
        )
        ActionType.WAIT -> copy(
            x = -1f, y = -1f, endX = -1f, endY = -1f, durationMs = 300L,
            targetText = "", regionLeft = 0, regionTop = 0, regionRight = 0, regionBottom = 0
        )
        ActionType.SWIPE -> copy(
            waitDurationMs = 1000L,
            targetText = "", regionLeft = 0, regionTop = 0, regionRight = 0, regionBottom = 0
        )
        ActionType.OCR_CHECK, ActionType.STOP_IF -> copy(
            x = -1f, y = -1f, endX = -1f, endY = -1f, durationMs = 300L, waitDurationMs = 1000L
        )
    }

    fun summary(): String = when (type) {
        ActionType.CLICK ->
            if (isClickConfigured) "点击 (${x.toInt()}, ${y.toInt()})" else "点击 · 未设置位置"
        ActionType.BACK -> "返回"
        ActionType.WAIT -> "等待 ${waitDurationMs} ms"
        ActionType.SWIPE ->
            if (isSwipeConfigured)
                "滑动 (${x.toInt()}, ${y.toInt()}) → (${endX.toInt()}, ${endY.toInt()}) ${durationMs}ms"
            else "滑动 · 未设置位置"
        ActionType.OCR_CHECK ->
            if (isRegionConfigured) "OCR \"$targetText\"" else "OCR · 未框选区域"
        ActionType.STOP_IF ->
            if (isRegionConfigured) "出现「$targetText」就停止" else "条件停止 · 未框选区域"
    }
}
