package io.github.shimo.smartclickocr.engine

import io.github.shimo.smartclickocr.model.ActionResult

/**
 * Performs the actual side effect of one action.
 *
 * Implementations must be suspending and return only once the action has really
 * finished (for gestures: when the system reports completion, or a timeout).
 * They must also cooperate with cancellation, which is how "stop" interrupts work.
 */
interface ActionExecutor {
    suspend fun execute(action: io.github.shimo.smartclickocr.model.AutomationAction): ActionResult
}

/**
 * Suspends for [ms]. Extracted so tests can replace real time with a virtual clock.
 */
fun interface Sleeper {
    suspend fun sleep(ms: Long)
}
