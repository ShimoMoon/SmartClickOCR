package io.github.shimo.smartclickocr.engine

import io.github.shimo.smartclickocr.model.ActionResult
import io.github.shimo.smartclickocr.model.ActionType
import io.github.shimo.smartclickocr.model.AutomationAction
import io.github.shimo.smartclickocr.model.EngineState
import io.github.shimo.smartclickocr.model.EngineStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Serial, looping automation engine.
 *
 * Rules implemented here (spec §4):
 *  - actions run in list order; only `enabled == true` actions are executed;
 *  - before EVERY action (the first one included) `delayBeforeMs` is awaited;
 *  - actions never overlap: the next starts only after [ActionExecutor.execute] returns;
 *  - after the last action the list restarts, forever, until stopped;
 *  - pause interrupts the pending delay and remembers the remainder, so resume
 *    continues the same action with its leftover delay (never restarts the list);
 *  - stop cancels the pending delay/action and prevents any further scheduling;
 *  - an OCR match ends the run with [EngineState.TARGET_FOUND];
 *  - any real failure ends the run with [EngineState.ERROR];
 *  - two loops can never run at the same time.
 *
 * Threading: call all methods from the thread that owns [scope] (main thread in the app).
 * The engine never blocks that thread.
 */
class ActionEngine(
    private val scope: CoroutineScope,
    private val executor: ActionExecutor,
    private val sleeper: Sleeper = Sleeper { delay(it) },
    /** Monotonic millisecond clock; virtualised in tests. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val logger: (String) -> Unit = {}
) {
    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private var job: Job? = null

    /** The action list currently running (or last run). */
    private var actions: List<AutomationAction> = emptyList()

    /** Index of the action that is (or will be) executed next. */
    private var cursor: Int = 0

    /** Remaining pre-delay of [cursor], preserved across pause. -1 means "none pending". */
    private var remainingDelayMs: Long = -1L

    val isRunning: Boolean get() = job?.isActive == true

    /**
     * Starts looping over [list].
     *
     * @param startPaused when true the task is "armed" but does not run yet: it enters
     *   [EngineState.PAUSED] at action 1 with its full pre-delay intact, so the user can
     *   switch to the target app (or another screen) before any gesture is dispatched.
     * @return `null` when started, otherwise a human readable reason it was refused.
     */
    fun start(
        list: List<AutomationAction>,
        profileName: String = "",
        startPaused: Boolean = false
    ): String? {
        // A PAUSED task has no running job but is still an active session, so check the
        // state as well: re-arming must not silently replace a task that is waiting.
        if (isRunning || _status.value.state.isActive) {
            return "任务已就绪或正在运行，请先停止后再重新开始"
        }
        val enabled = list.filter { it.enabled }
        if (enabled.isEmpty()) return "没有启用的动作，无法启动"
        validate(enabled)?.let { return it }

        actions = enabled
        cursor = 0
        remainingDelayMs = -1L
        setStatus(
            EngineStatus(
                state = if (startPaused) EngineState.PAUSED else EngineState.RUNNING,
                round = 1,
                actionIndex = if (startPaused) 0 else -1,
                actionCount = enabled.size,
                actionLabel = if (startPaused) enabled[0].summary() else "",
                message = if (startPaused) "已就绪，点「继续」开始执行" else "任务已启动"
            )
        )
        log(
            if (startPaused) {
                "任务已就绪（未开始执行）" + if (profileName.isNotBlank()) "（方案：$profileName）" else ""
            } else {
                "任务启动" + if (profileName.isNotBlank()) "（方案：$profileName）" else ""
            }
        )
        // An armed-but-paused task has no running job; resume() launches the loop.
        if (!startPaused) launchLoop(resume = false)
        return null
    }

    /** Pauses the loop; a pending delay is interrupted and its remainder kept. */
    fun pause() {
        if (_status.value.state != EngineState.RUNNING) return
        job?.cancel()
        job = null
        setStatus(_status.value.copy(state = EngineState.PAUSED, message = "已暂停"))
        log("已暂停")
    }

    /** Resumes exactly where pause interrupted, keeping the leftover delay. */
    fun resume() {
        if (_status.value.state != EngineState.PAUSED) return
        if (isRunning) return
        if (actions.isEmpty()) return
        setStatus(_status.value.copy(state = EngineState.RUNNING, message = "继续运行"))
        log("继续运行")
        launchLoop(resume = true)
    }

    /** Stops for good: cancels the pending delay and blocks later actions. */
    fun stop() {
        val wasActive = isRunning || _status.value.state == EngineState.PAUSED
        job?.cancel()
        job = null
        remainingDelayMs = -1L
        cursor = 0
        if (wasActive) {
            setStatus(_status.value.copy(state = EngineState.STOPPED, message = "已停止"))
            log("用户停止任务")
        } else if (!_status.value.state.isTerminal()) {
            setStatus(EngineStatus(state = EngineState.STOPPED, message = "已停止"))
        }
    }

    /** Clears a terminal state back to IDLE. */
    fun reset() {
        job?.cancel()
        job = null
        remainingDelayMs = -1L
        cursor = 0
        setStatus(EngineStatus(state = EngineState.IDLE, actionCount = actions.size))
    }

    private fun launchLoop(resume: Boolean) {
        job = scope.launch { runLoop(resume) }
    }

    private suspend fun runLoop(resume: Boolean) {
        val list = actions
        var round = _status.value.round.coerceAtLeast(1)
        if (!resume) log("第 1 轮开始")
        setStatus(
            _status.value.copy(
                state = EngineState.RUNNING,
                round = round,
                actionCount = list.size
            )
        )

        try {
            while (scope.isActive) {
                val index = cursor.coerceIn(0, list.size - 1)
                val action = list[index]

                // 1) Pre-delay. The very first action waits too; on resume the remainder is used.
                val toWait = if (remainingDelayMs >= 0L) remainingDelayMs else action.delayBeforeMs
                remainingDelayMs = -1L
                if (toWait > 0L) {
                    setStatus(
                        _status.value.copy(
                            actionIndex = index,
                            actionLabel = action.summary(),
                            message = "等待 ${toWait}ms 后执行"
                        )
                    )
                    waitWithRemainder(toWait)
                }

                // 2) Execute strictly serially.
                setStatus(
                    _status.value.copy(
                        actionIndex = index,
                        actionLabel = action.summary(),
                        message = "执行动作 ${index + 1}：${action.type.displayName}"
                    )
                )
                log("执行动作 ${index + 1}：${action.type.displayName}")

                val result = executor.execute(action)
                if (result == ActionResult.StopMatched) {
                    val stopReason =
                        if (action.type == ActionType.STOP_IF) {
                            "停止条件「${action.targetText}」已满足，自动停止"
                        } else {
                            "命中目标文字「${action.targetText}」，自动停止"
                        }
                    finish(
                        _status.value.copy(
                            state = EngineState.TARGET_FOUND,
                            actionIndex = index,
                            message = stopReason.removeSuffix("，自动停止")
                        )
                    )
                    log(stopReason)
                    return
                }
                if (result is ActionResult.Error) {
                    finish(
                        _status.value.copy(
                            state = EngineState.ERROR,
                            actionIndex = index,
                            message = "动作失败",
                            errorMessage = result.message
                        )
                    )
                    log("动作失败：${result.message}")
                    return
                }
                if (action.type.isOcrBased) {
                    log(
                        if (action.type == ActionType.STOP_IF) {
                            "停止条件「${action.targetText}」未满足，继续执行"
                        } else {
                            "OCR 未匹配「${action.targetText}」，继续执行"
                        }
                    )
                }

                // 3) Advance, wrapping forever.
                cursor = index + 1
                if (cursor >= list.size) {
                    cursor = 0
                    round++
                    log("第 $round 轮开始")
                    setStatus(_status.value.copy(round = round, actionIndex = -1))
                }

                // 4) Always hand control back before the next iteration.
                yieldToDispatcher()
            }
        } catch (e: CancellationException) {
            // Pause/stop already published the new state.
            throw e
        } catch (e: Exception) {
            finish(
                _status.value.copy(
                    state = EngineState.ERROR,
                    message = "引擎异常",
                    errorMessage = e.message ?: e.javaClass.simpleName
                )
            )
            log("引擎异常：${e.message}")
        }
    }

    /**
     * Waits [ms] as a single sleep. Cancellation (pause/stop) records the remainder
     * so resuming continues this same action instead of restarting it.
     * A single sleep can never busy-loop, even with a virtual clock.
     */
    private suspend fun waitWithRemainder(ms: Long) {
        val deadline = clock() + ms
        remainingDelayMs = ms
        try {
            sleeper.sleep(ms)
        } catch (e: CancellationException) {
            remainingDelayMs = (deadline - clock()).coerceAtLeast(0L)
            throw e
        }
        remainingDelayMs = -1L
    }

    /**
     * Hands control back to the dispatcher once per loop iteration.
     *
     * Why not a plain `yield()`: yield re-queues the continuation as *immediately
     * ready* work, so a list of zero-delay actions would starve its dispatcher
     * (pause/stop could be delayed indefinitely), and virtual-time test drivers —
     * which only move the clock when a scheduled task's time is reached — would spin
     * forever at t=0. Dispatching through a 1ms delay guarantees the scheduler can
     * run other suspended work and advance time. The cost is a 1ms tick between
     * iterations, far below any realistic action delay.
     */
    private suspend fun yieldToDispatcher() {
        delay(LOOP_TICK_MS)
    }

    private fun finish(status: EngineStatus) {
        remainingDelayMs = -1L
        cursor = 0
        job = null
        setStatus(status)
    }

    private fun validate(list: List<AutomationAction>): String? {
        list.forEachIndexed { i, a ->
            val label = "动作 ${i + 1}（${a.type.displayName}）"
            if (a.delayBeforeMs < 0) return "$label 的执行前延迟不能为负数"
            when (a.type) {
                ActionType.CLICK -> if (!a.isClickConfigured) return "$label 尚未设置点击位置"
                ActionType.SWIPE -> {
                    if (!a.isSwipeConfigured) return "$label 尚未设置滑动起点和终点"
                    if (a.durationMs <= 0) return "$label 的滑动时长必须大于 0"
                }
                ActionType.WAIT -> if (a.waitDurationMs < 0) {
                    return "$label 的等待时长不能为负数"
                }
                ActionType.OCR_CHECK, ActionType.STOP_IF -> {
                    if (!a.isRegionConfigured) return "$label 尚未框选识别区域"
                    if (a.targetText.trim().isEmpty()) return "$label 的目标文字不能为空"
                }
                ActionType.BACK -> Unit
            }
        }
        return null
    }

    private fun setStatus(status: EngineStatus) {
        _status.value = status
    }

    private fun log(message: String) {
        runCatching { logger(message) }
    }

    private companion object {
        /** Dispatcher tick between loop iterations; keeps the loop from starving its dispatcher. */
        const val LOOP_TICK_MS = 1L
    }

    private fun EngineState.isTerminal(): Boolean =
        this == EngineState.STOPPED || this == EngineState.TARGET_FOUND ||
            this == EngineState.ERROR || this == EngineState.IDLE
}
