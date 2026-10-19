package io.github.shimo.smartclickocr.engine

import io.github.shimo.smartclickocr.model.ActionResult
import io.github.shimo.smartclickocr.model.ActionType
import io.github.shimo.smartclickocr.model.AutomationAction
import io.github.shimo.smartclickocr.model.EngineState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * Engine tests driven by a virtual clock and a fake executor.
 * No real screen is touched (spec stage B.4).
 *
 * The engine owns its own scope and its own virtual scheduler, mirroring the real
 * setup where a long-lived service owns the loop.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActionEngineTest {

    private lateinit var executed: MutableList<String>
    private lateinit var logLines: MutableList<String>
    private lateinit var engineScope: CoroutineScope
    private lateinit var engineScheduler: TestCoroutineScheduler

    private fun click(id: String, delay: Long = 0L) =
        AutomationAction(id = id, type = ActionType.CLICK, delayBeforeMs = delay, x = 10f, y = 20f)

    private fun waitAction(id: String, delay: Long = 0L, duration: Long = 100L) =
        AutomationAction(id = id, type = ActionType.WAIT, delayBeforeMs = delay, waitDurationMs = duration)

    private fun ocrAction(id: String, delay: Long = 0L, target: String = "任务完成") =
        AutomationAction(
            id = id, type = ActionType.OCR_CHECK, delayBeforeMs = delay,
            targetText = target, regionLeft = 0, regionTop = 0, regionRight = 100, regionBottom = 50
        )

    private fun stopIfAction(id: String, delay: Long = 0L, target: String = "任务完成") =
        AutomationAction(
            id = id, type = ActionType.STOP_IF, delayBeforeMs = delay,
            targetText = target, regionLeft = 0, regionTop = 0, regionRight = 100, regionBottom = 50
        )

    private class RecordingExecutor(
        private val executed: MutableList<String>,
        private val results: Map<String, () -> ActionResult> = emptyMap(),
        private val costMs: Long = 0L
    ) : ActionExecutor {
        override suspend fun execute(action: AutomationAction): ActionResult {
            executed.add(action.id)
            if (costMs > 0) delay(costMs)
            return results[action.id]?.invoke() ?: ActionResult.Continue
        }
    }

    @Before
    fun setUp() {
        executed = CopyOnWriteArrayList()
        logLines = CopyOnWriteArrayList()
    }

    @After
    fun tearDown() {
        engineScope.cancel()
    }

    private fun makeEngine(exec: ActionExecutor = RecordingExecutor(executed)): ActionEngine {
        engineScheduler = TestCoroutineScheduler()
        engineScope = CoroutineScope(StandardTestDispatcher(engineScheduler))
        return ActionEngine(
            scope = engineScope,
            executor = exec,
            sleeper = Sleeper { delay(it) },
            clock = { engineScheduler.currentTime },
            logger = { logLines.add(it) }
        )
    }

    @Test
    fun `refuses to start when no actions are enabled`() = runTest(timeout = 30.seconds) {
        val engine = makeEngine()
        assertNotNull("expected a refusal reason", engine.start(listOf(click("a").copy(enabled = false))))
        assertEquals(EngineState.IDLE, engine.status.value.state)
        assertTrue(executed.isEmpty())
    }

    @Test
    fun `refuses to start twice`() = runTest(timeout = 30.seconds) {
        val engine = makeEngine()
        assertNull(engine.start(listOf(waitAction("w1", duration = 100))))
        assertNotNull("second start must be refused", engine.start(listOf(waitAction("w2"))))
        engineScheduler.advanceTimeBy(50)
        engine.stop()
        engineScheduler.advanceTimeBy(1000)
        // Only the first list ever ran; "w2" never appears.
        assertTrue(executed.isNotEmpty())
        assertTrue("a second loop must never start", executed.all { it == "w1" })
    }

    @Test
    fun `first action also waits its pre-delay`() = runTest(timeout = 30.seconds) {
        val engine = makeEngine()
        engine.start(listOf(click("a", delay = 1000), click("b", delay = 500)))

        engineScheduler.advanceTimeBy(999)
        assertTrue("nothing runs before the first delay elapses", executed.isEmpty())

        engineScheduler.advanceTimeBy(2)
        assertEquals(listOf("a"), executed)

        engineScheduler.advanceTimeBy(499)
        assertEquals(listOf("a"), executed)

        engineScheduler.advanceTimeBy(2)
        assertEquals(listOf("a", "b"), executed)

        engine.stop()
    }

    @Test
    fun `actions run serially and never overlap`() = runTest(timeout = 30.seconds) {
        val overlap = AtomicBoolean(false)
        val active = AtomicInteger(0)
        val exec = object : ActionExecutor {
            override suspend fun execute(action: AutomationAction): ActionResult {
                if (active.incrementAndGet() > 1) overlap.set(true)
                executed.add(action.id)
                delay(100)
                active.decrementAndGet()
                return ActionResult.Continue
            }
        }
        val engine = makeEngine(exec)
        engine.start(listOf(click("a"), click("b"), click("c")))
        engineScheduler.advanceTimeBy(340)
        // a@0-100, b@~101-201, c@~202-302. The 2nd round's "a" starts at ~303 and is
        // still running at 340, so exactly four executions must have started.
        engine.stop()
        engineScheduler.advanceTimeBy(1000)

        assertEquals(listOf("a", "b", "c", "a"), executed)
        assertTrue("actions must never overlap", !overlap.get())
    }

    @Test
    fun `loop wraps around forever`() = runTest(timeout = 30.seconds) {
        val engine = makeEngine()
        // Give the action a real pre-delay so each round is clearly separated in time.
        engine.start(listOf(waitAction("w1", delay = 100, duration = 0)))
        // Never use advanceUntilIdle here: an endless loop has no idle state.
        engineScheduler.advanceTimeBy(350)
        val roundAtStop = engine.status.value.round
        engine.stop()
        engineScheduler.advanceTimeBy(1000)

        // Each round runs at ~0ms, ~101ms, ~201ms (100ms delay + the 1ms loop tick),
        // so three executions are due within 350ms and the loop keeps going on its own.
        assertTrue("the list must repeat automatically, was $executed", executed.size >= 3)
        assertTrue("every round repeats the same action", executed.all { it == "w1" })
        assertTrue("rounds keep increasing, was $roundAtStop", roundAtStop >= 2)
        assertEquals(EngineState.STOPPED, engine.status.value.state)
    }

    @Test
    fun `pause during delay prevents further actions`() = runTest(timeout = 30.seconds) {
        val engine = makeEngine()
        engine.start(listOf(click("a", delay = 1000), click("b", delay = 1000)))

        engineScheduler.advanceTimeBy(500)
        engine.pause()
        assertEquals(EngineState.PAUSED, engine.status.value.state)

        engineScheduler.advanceTimeBy(10_000)
        assertTrue("no action may run while paused", executed.isEmpty())
        assertEquals(EngineState.PAUSED, engine.status.value.state)
    }

    @Test
    fun `pause after an action completes does not run the next one`() = runTest(timeout = 30.seconds) {
        val engine = makeEngine(RecordingExecutor(executed, costMs = 100))
        engine.start(listOf(click("a", delay = 0), click("b", delay = 5000)))

        engineScheduler.advanceTimeBy(150) // a finished, b is inside its pre-delay
        engine.pause()
        engineScheduler.advanceTimeBy(20_000)

        assertEquals(listOf("a"), executed)
        assertEquals(EngineState.PAUSED, engine.status.value.state)
    }

    @Test
    fun `resume continues with the remaining delay not the full delay`() = runTest(timeout = 30.seconds) {
        val engine = makeEngine()
        engine.start(listOf(click("a", delay = 1000), click("b", delay = 5000)))

        engineScheduler.advanceTimeBy(700) // 700ms of a's 1000ms delay elapsed
        engine.pause()
        engineScheduler.advanceTimeBy(5_000)

        engine.resume()
        engineScheduler.advanceTimeBy(250)
        assertTrue("still waiting the remaining ~300ms of a", executed.isEmpty())

        engineScheduler.advanceTimeBy(100)
        assertEquals(listOf("a"), executed)

        engine.stop()
    }

    @Test
    fun `stop interrupts the pending delay`() = runTest(timeout = 30.seconds) {
        val engine = makeEngine()
        engine.start(listOf(click("a", delay = 10_000)))
        engineScheduler.advanceTimeBy(100)
        engine.stop()
        assertEquals(EngineState.STOPPED, engine.status.value.state)

        engineScheduler.advanceTimeBy(60_000)
        assertTrue("no action after stop", executed.isEmpty())
        assertEquals(EngineState.STOPPED, engine.status.value.state)
    }

    @Test
    fun `ocr match stops the whole task`() = runTest(timeout = 30.seconds) {
        val engine = makeEngine(RecordingExecutor(executed, mapOf("ocr" to { ActionResult.StopMatched })))
        engine.start(listOf(click("a"), ocrAction("ocr"), click("c")))
        engineScheduler.advanceUntilIdle()

        assertEquals(listOf("a", "ocr"), executed)
        assertEquals(EngineState.TARGET_FOUND, engine.status.value.state)
        assertTrue("matched OCR never runs later actions", !executed.contains("c"))
    }

    @Test
    fun `ocr mismatch continues to later actions`() = runTest(timeout = 30.seconds) {
        val engine = makeEngine(RecordingExecutor(executed, mapOf("ocr" to { ActionResult.Continue })))
        // A real pre-delay keeps each round distinct so the ordering can be asserted.
        engine.start(listOf(ocrAction("ocr", delay = 100), click("c", delay = 100)))
        engineScheduler.advanceTimeBy(250)
        engine.stop()
        engineScheduler.advanceTimeBy(1000)

        assertEquals(listOf("ocr", "c"), executed)
    }

    @Test
    fun `ocr mismatch is not an error`() = runTest(timeout = 30.seconds) {
        val engine = makeEngine(RecordingExecutor(executed, mapOf("ocr" to { ActionResult.Continue })))
        engine.start(listOf(ocrAction("ocr", delay = 1000)))
        engineScheduler.advanceTimeBy(500)

        assertEquals(EngineState.RUNNING, engine.status.value.state)
        assertNull(engine.status.value.errorMessage)
        engine.stop()
    }

    @Test
    fun `action error stops immediately and reports the message`() = runTest(timeout = 30.seconds) {
        val engine = makeEngine(
            RecordingExecutor(executed, mapOf("bad" to { ActionResult.Error("截图失败：会话已失效") }))
        )
        engine.start(listOf(click("a"), click("bad"), click("c")))
        engineScheduler.advanceUntilIdle()

        assertEquals(listOf("a", "bad"), executed)
        assertEquals(EngineState.ERROR, engine.status.value.state)
        assertEquals("截图失败：会话已失效", engine.status.value.errorMessage)
        assertTrue(!executed.contains("c"))
    }

    @Test
    fun `disabled actions are skipped`() = runTest(timeout = 30.seconds) {
        val engine = makeEngine()
        engine.start(
            listOf(
                click("a", delay = 100),
                click("skip").copy(enabled = false),
                waitAction("w3", delay = 100, duration = 0)
            )
        )
        engineScheduler.advanceTimeBy(250)
        engine.stop()
        engineScheduler.advanceTimeBy(1000)

        assertEquals(listOf("a", "w3"), executed)
    }

    @Test
    fun `validation rejects unconfigured actions`() = runTest(timeout = 30.seconds) {
        val engine = makeEngine()
        assertNotNull(engine.start(listOf(AutomationAction(type = ActionType.CLICK))))
        assertNotNull(
            engine.start(
                listOf(
                    AutomationAction(
                        type = ActionType.OCR_CHECK, targetText = "  ",
                        regionLeft = 0, regionTop = 0, regionRight = 10, regionBottom = 10
                    )
                )
            )
        )
        assertNotNull(
            engine.start(
                listOf(
                    AutomationAction(
                        type = ActionType.SWIPE, x = 0f, y = 0f, endX = 5f, endY = 5f, durationMs = 0
                    )
                )
            )
        )
    }

    @Test
    fun `stop condition matching ends the task`() = runTest(timeout = 30.seconds) {
        val engine = makeEngine(
            RecordingExecutor(executed, mapOf("stop" to { ActionResult.StopMatched }))
        )
        engine.start(listOf(click("a"), stopIfAction("stop"), click("c")))
        engineScheduler.advanceUntilIdle()

        assertEquals(listOf("a", "stop"), executed)
        assertEquals(EngineState.TARGET_FOUND, engine.status.value.state)
        assertTrue("actions after a satisfied stop condition never run", !executed.contains("c"))
    }

    @Test
    fun `stop condition not matching lets the loop continue`() = runTest(timeout = 30.seconds) {
        val engine = makeEngine(
            RecordingExecutor(executed, mapOf("stop" to { ActionResult.Continue }))
        )
        engine.start(
            listOf(stopIfAction("stop", delay = 100), click("c", delay = 100), click("d", delay = 5000))
        )
        engineScheduler.advanceTimeBy(250)
        engine.stop()
        engineScheduler.advanceTimeBy(1000)

        assertEquals(listOf("stop", "c"), executed)
    }

    @Test
    fun `stop condition is validated like an ocr check`() = runTest(timeout = 30.seconds) {
        val engine = makeEngine()
        // No region selected -> refused.
        assertNotNull(engine.start(listOf(AutomationAction(type = ActionType.STOP_IF, targetText = "x"))))
        // Empty target -> refused.
        assertNotNull(
            engine.start(
                listOf(
                    AutomationAction(
                        type = ActionType.STOP_IF, targetText = "  ",
                        regionLeft = 0, regionTop = 0, regionRight = 10, regionBottom = 10
                    )
                )
            )
        )
    }

    @Test
    fun `stop condition failure is a real error not a match`() = runTest(timeout = 30.seconds) {
        val engine = makeEngine(
            RecordingExecutor(executed, mapOf("stop" to { ActionResult.Error("截图失败") }))
        )
        engine.start(listOf(stopIfAction("stop"), click("c")))
        engineScheduler.advanceUntilIdle()

        assertEquals(EngineState.ERROR, engine.status.value.state)
        assertEquals("截图失败", engine.status.value.errorMessage)
        assertTrue(!executed.contains("c"))
    }

    @Test
    fun `start then start again works`() = runTest(timeout = 30.seconds) {
        val engine = makeEngine()
        engine.start(listOf(waitAction("w1", duration = 0)))
        engineScheduler.advanceTimeBy(10)
        engine.stop()
        engineScheduler.advanceTimeBy(10)
        assertEquals(EngineState.STOPPED, engine.status.value.state)

        assertNull("engine must be restartable after stop", engine.start(listOf(waitAction("w2", duration = 0))))
        engineScheduler.advanceTimeBy(10)
        assertEquals("w2", executed.last())
        engine.stop()
    }

    @Test
    fun `startPaused arms the task without running any action`() = runTest(timeout = 30.seconds) {
        val engine = makeEngine()
        // Arming must not dispatch anything: the user still has to switch apps.
        assertNull(engine.start(listOf(click("a", delay = 0), click("b", delay = 0)), startPaused = true))

        assertEquals(EngineState.PAUSED, engine.status.value.state)
        engineScheduler.advanceTimeBy(10_000)
        assertTrue("no action may run while merely armed", executed.isEmpty())

        // Resuming runs the first action with its pre-delay still applied.
        engine.resume()
        engineScheduler.advanceTimeBy(10)
        assertTrue("the first action must run after resuming, was $executed", executed.isNotEmpty())
        assertEquals("a", executed.first())
        engine.stop()
    }

    @Test
    fun `startPaused then resume honours the first action pre-delay`() = runTest(timeout = 30.seconds) {
        val engine = makeEngine()
        engine.start(listOf(click("a", delay = 500)), startPaused = true)
        engineScheduler.advanceTimeBy(2_000)
        assertTrue(executed.isEmpty())

        engine.resume()
        engineScheduler.advanceTimeBy(400)
        assertTrue("must still wait the 500ms pre-delay after arming", executed.isEmpty())
        engineScheduler.advanceTimeBy(150)
        assertEquals(listOf("a"), executed)
        engine.stop()
    }

    @Test
    fun `armed task reports progress pointing at the first action`() = runTest(timeout = 30.seconds) {
        val engine = makeEngine()
        engine.start(listOf(click("a"), waitAction("w2", duration = 0)), startPaused = true)
        val status = engine.status.value
        assertEquals(EngineState.PAUSED, status.state)
        assertEquals(0, status.actionIndex)
        assertEquals(2, status.actionCount)
        assertTrue("labels the pending action: ${status.actionLabel}", status.actionLabel.isNotBlank())
    }

    @Test
    fun `arming twice is refused and does not double start`() = runTest(timeout = 30.seconds) {
        val engine = makeEngine()
        assertNull(engine.start(listOf(waitAction("w1", duration = 100)), startPaused = true))
        // A second start while PAUSED must be refused, not silently replace the task.
        assertNotNull("arming again must be refused", engine.start(listOf(waitAction("w2")), startPaused = true))
        engineScheduler.advanceTimeBy(1_000)
        assertTrue(executed.isEmpty())
    }
}
