package io.github.shimo.smartclickocr.model

/**
 * Outcome of a single action execution.
 *  - [Continue]    : the action succeeded, the engine continues with the next action.
 *  - [StopMatched] : the OCR check matched the target text; the whole task stops normally.
 *  - [Error]       : the action could not be performed; the whole task stops with an error.
 *
 * "OCR text not found" is NOT an error; it is a normal [Continue].
 */
sealed class ActionResult {
    data object Continue : ActionResult()
    data object StopMatched : ActionResult()
    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : ActionResult()
}

/**
 * Lifecycle state of the automation engine.
 */
enum class EngineState {
    IDLE,
    RUNNING,
    PAUSED,
    STOPPED,
    TARGET_FOUND,
    ERROR;

    val displayName: String
        get() = when (this) {
            IDLE -> "空闲"
            RUNNING -> "运行中"
            PAUSED -> "已暂停"
            STOPPED -> "已停止"
            TARGET_FOUND -> "命中目标"
            ERROR -> "出错"
        }

    val isActive: Boolean get() = this == RUNNING || this == PAUSED
}

/**
 * Snapshot of engine progress, rendered by both the main screen and the floating panel.
 */
data class EngineStatus(
    val state: EngineState = EngineState.IDLE,
    val round: Int = 0,
    val actionIndex: Int = -1,
    val actionCount: Int = 0,
    val actionLabel: String = "",
    val message: String = "",
    val errorMessage: String? = null
) {
    fun progressText(): String = buildString {
        if (round > 0) append("第 $round 轮")
        if (actionIndex >= 0 && actionCount > 0) {
            if (isNotEmpty()) append(" · ")
            append("动作 ${actionIndex + 1}/$actionCount")
        }
    }
}
