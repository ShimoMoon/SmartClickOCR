package io.github.shimo.smartclickocr.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.shimo.smartclickocr.model.AutomationAction
import io.github.shimo.smartclickocr.model.EngineState
import io.github.shimo.smartclickocr.model.EngineStatus
import io.github.shimo.smartclickocr.util.CoordinateMapper
import io.github.shimo.smartclickocr.util.ScreenMetricsProvider
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * All WindowManager overlays in one place:
 *  - the floating control button + expandable panel (spec §5);
 *  - the draggable crosshair used to pick a tap/swipe point (spec §7.2.1);
 *  - the draggable/resizable rectangle for OCR regions (spec §7.2.3);
 *  - the short-lived ring shown at a tapped point (spec §7.2.4).
 *
 * Overlays never send touches to the app underneath: control windows are
 * non-focusable and the picker windows only receive their own touches, so
 * calibration cannot accidentally trigger the target app.
 */
class OverlayController(
    private val context: Context,
    private val scope: CoroutineScope
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /** What a calibration overlay is being used for. */
    enum class Mode { PICK_POINT, PICK_REGION }

    private var bubbleView: View? = null
    private var bubbleStatusView: TextView? = null
    private var panelView: View? = null
    private var calibrationView: View? = null
    private var feedbackViews = mutableListOf<View>()

    private var bubbleParams: WindowManager.LayoutParams? = null

    /** Called when the user confirms a point. Receives screen coordinates. */
    var onPointPicked: ((Float, Float) -> Unit)? = null
    var onRegionPicked: ((Int, Int, Int, Int) -> Unit)? = null
    var onCalibrationCancelled: (() -> Unit)? = null
    var onPanelStart: (() -> Unit)? = null
    var onPanelPause: (() -> Unit)? = null
    var onPanelResume: (() -> Unit)? = null
    var onPanelStop: (() -> Unit)? = null
    var onPanelClose: (() -> Unit)? = null

    private val overlayType: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    // ------------------------------------------------------ floating control

    @SuppressLint("ClickableViewAccessibility")
    fun showControl() {
        if (bubbleView != null) return
        val metrics = ScreenMetricsProvider.current()
        val size = dp(56)
        val view = TextView(context).apply {
            text = "待命"
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC1565C0"))
            }
            elevation = dp(6).toFloat()
        }
        bubbleStatusView = view
        val params = WindowManager.LayoutParams(
            size, size,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val saved = FloatingPositionStore.load(context)
            x = if (saved.first >= 0) saved.first else max(0, metrics.widthPx - size - dp(8))
            y = if (saved.second >= 0) saved.second else dp(120)
        }

        var startX = 0
        var startY = 0
        var startTouchX = 0f
        var startTouchY = 0f
        var moved = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    startTouchX = event.rawX
                    startTouchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startTouchX).toInt()
                    val dy = (event.rawY - startTouchY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) {
                        params.x = startX + dx
                        params.y = startY + dy
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        FloatingPositionStore.save(context, params.x, params.y)
                    } else {
                        togglePanel()
                    }
                    true
                }
                else -> false
            }
        }
        runCatching {
            windowManager.addView(view, params)
            bubbleView = view
            bubbleParams = params
        }
    }

    private fun togglePanel() {
        if (panelView != null) {
            hidePanel()
        } else {
            showPanel()
        }
    }

    private fun hidePanel() {
        panelView?.let { runCatching { windowManager.removeView(it) } }
        panelView = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showPanel() {
        val (screenW, screenH) = ScreenMetricsProvider.current().let { it.widthPx to it.heightPx }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#F2202124"))
                setStroke(dp(1), Color.parseColor("#55FFFFFF"))
            }
        }

        val title = TextView(context).apply {
            text = "SmartClickOCR"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val statusText = TextView(context).apply {
            id = View.generateViewId()
            tag = "status"
            setTextColor(Color.parseColor("#FFCC80"))
            textSize = 13f
            setPadding(0, dp(6), 0, dp(8))
        }
        val detailText = TextView(context).apply {
            tag = "detail"
            setTextColor(Color.parseColor("#B0BEC5"))
            textSize = 12f
        }
        val progressText = TextView(context).apply {
            tag = "progress"
            setTextColor(Color.parseColor("#90CAF9"))
            textSize = 12f
            setPadding(0, dp(4), 0, dp(8))
        }

        // Row 1: start / pause / resume / stop. Row 2: collapse.
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val startBtn = makeButton("开始") { onPanelStart?.invoke(); refreshPanel() }
        val pauseBtn = makeButton("暂停") { onPanelPause?.invoke(); refreshPanel() }
        val resumeBtn = makeButton("继续") { onPanelResume?.invoke(); refreshPanel() }
        val stopBtn = makeButton("停止") { onPanelStop?.invoke(); refreshPanel() }
        listOf(startBtn, pauseBtn, resumeBtn, stopBtn).forEachIndexed { i, b ->
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (i > 0) lp.leftMargin = dp(6)
            row.addView(b, lp)
        }
        val row2 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val closeBtn = makeButton("收起") { hidePanel() }
        row2.addView(closeBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        root.addView(title)
        root.addView(statusText)
        root.addView(progressText)
        root.addView(detailText)
        root.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
        root.addView(row2, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        val params = WindowManager.LayoutParams(
            min(screenW - dp(24), dp(300)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(150)
        }
        runCatching {
            windowManager.addView(root, params)
            panelView = root
            renderPanel(AutomationServiceStatusHolder.current())
        }
    }

    private fun makeButton(text: String, onClick: () -> Unit): Button =
        Button(context).apply {
            this.text = text
            textSize = 13f
            isAllCaps = false
            setOnClickListener { onClick() }
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

    private fun refreshPanel() {
        if (panelView != null) renderPanel(AutomationServiceStatusHolder.current())
    }

    private fun renderPanel(status: EngineStatus) {
        val root = panelView ?: return
        val statusText = root.findViewWithTag<TextView>("status")
        val progressText = root.findViewWithTag<TextView>("progress")
        val detailText = root.findViewWithTag<TextView>("detail")
        statusText?.text = "状态：${status.state.displayName}"
        progressText?.text = status.progressText().ifBlank { "等待启动" }
        detailText?.text = status.errorMessage ?: status.message

        // Row 1 holds start/pause/resume/stop (row 2 is the collapse button).
        val row = ((root as LinearLayout).getChildAt(root.childCount - 2) as? LinearLayout) ?: return
        val start = row.getChildAt(0)
        val pause = row.getChildAt(1)
        val resume = row.getChildAt(2)
        val stop = row.getChildAt(3)

        // 开始 is offered whenever nothing is looping (idle, stopped, matched or error),
        // so the user can arm the task from the floating panel after switching apps.
        start?.isEnabled = !status.state.isActive && status.actionCount > 0
        pause?.isEnabled = status.state == EngineState.RUNNING
        resume?.isEnabled = status.state == EngineState.PAUSED
        stop?.isEnabled = status.state.isActive
    }

    fun onStatusChanged(status: EngineStatus) {
        AutomationServiceStatusHolder.update(status)
        renderBubble(status)
        refreshPanel()
    }

    /**
     * The collapsed bubble shows the state and changes colour, so the user can tell at a
     * glance whether gestures are being dispatched without opening the panel.
     */
    private fun renderBubble(status: EngineStatus) {
        val view = bubbleStatusView ?: return
        val (label, color) = when (status.state) {
            EngineState.RUNNING -> "运行" to "#CC1B5E20"
            EngineState.PAUSED -> "待命" to "#CCEF6C00"
            EngineState.TARGET_FOUND -> "命中" to "#CC00695C"
            EngineState.ERROR -> "出错" to "#CCC62828"
            EngineState.STOPPED -> "停止" to "#CC424242"
            EngineState.IDLE -> "待命" to "#CC1565C0"
        }
        view.text = label
        (view.background as? GradientDrawable)?.setColor(Color.parseColor(color))
    }

    fun showFinishedState(status: EngineStatus) {
        if (status.state == EngineState.TARGET_FOUND || status.state == EngineState.ERROR ||
            status.state == EngineState.STOPPED
        ) {
            // Keep the bubble so the user can read the result; closing must not restart.
            showControl()
            refreshPanel()
        }
    }

    // ------------------------------------------------------- tap feedback

    fun showTapFeedback(screenX: Float, screenY: Float) {
        val size = dp(48)
        val ring = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dp(3), Color.parseColor("#FF3D00"))
            }
        }
        val params = WindowManager.LayoutParams(
            size, size, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenX - size / 2f).toInt()
            y = (screenY - size / 2f).toInt()
        }
        runCatching {
            windowManager.addView(ring, params)
            feedbackViews.add(ring)
        }
        ring.postDelayed({
            runCatching { windowManager.removeView(ring) }
            feedbackViews.remove(ring)
        }, 450)
    }

    // -------------------------------------------------------- calibration

    fun showMode(mode: Mode) = runCatching {
        hideCalibration()
        if (mode == Mode.PICK_POINT) showCrosshair() else showRegionSelector()
    }.getOrNull()

    fun hideCalibration() {
        calibrationView?.let { runCatching { windowManager.removeView(it) } }
        calibrationView = null
        // The confirm bar is a separate window; leaving it behind would block the screen.
        calibrationViewExtra?.let { runCatching { windowManager.removeView(it) } }
        calibrationViewExtra = null
        regionRect = null
    }

    /** Draggable crosshair; confirming does NOT click the app underneath. */
    @SuppressLint("ClickableViewAccessibility")
    private fun showCrosshair() {
        val metrics = ScreenMetricsProvider.current()
        val ringSize = dp(80)
        val root = FrameLayout(context)
        val ring = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#33FF3D00"))
                setStroke(dp(2), Color.parseColor("#FF3D00"))
            }
        }
        val crossH = View(context).apply { setBackgroundColor(Color.parseColor("#FF3D00")) }
        val crossV = View(context).apply { setBackgroundColor(Color.parseColor("#FF3D00")) }
        root.addView(ring, FrameLayout.LayoutParams(ringSize, ringSize, Gravity.CENTER))
        root.addView(crossH, FrameLayout.LayoutParams(dp(40), dp(2), Gravity.CENTER))
        root.addView(crossV, FrameLayout.LayoutParams(dp(2), dp(40), Gravity.CENTER))

        val params = WindowManager.LayoutParams(
            ringSize, ringSize, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = metrics.widthPx / 2 - ringSize / 2
            y = metrics.heightPx / 2 - ringSize / 2
        }

        // Drag by tracking the movement delta, so the crosshair follows the finger 1:1.
        var lastX = 0f
        var lastY = 0f
        root.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.rawX
                    lastY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - lastX).toInt()
                    val dy = (e.rawY - lastY).toInt()
                    if (dx != 0 || dy != 0) {
                        lastX = e.rawX
                        lastY = e.rawY
                        params.x = (params.x + dx).coerceIn(
                            -ringSize / 2,
                            (metrics.widthPx - ringSize / 2).coerceAtLeast(-ringSize / 2)
                        )
                        params.y = (params.y + dy).coerceIn(
                            -ringSize / 2,
                            (metrics.heightPx - ringSize / 2).coerceAtLeast(-ringSize / 2)
                        )
                        runCatching { windowManager.updateViewLayout(root, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // Keep the crosshair where the user left it; confirmation is separate.
                    true
                }
                else -> true
            }
        }

        val bar = buildCalibrationBar(
            hint = "拖动准星到目标中心，再点「确认位置」",
            onConfirm = {
                val cx = params.x + ringSize / 2f
                val cy = params.y + ringSize / 2f
                val (sxScreen, syScreen) = CoordinateMapper.overlayToScreen(cx, cy)
                hideCalibration()
                onPointPicked?.invoke(sxScreen, syScreen)
            },
            onCancel = {
                hideCalibration()
                onCalibrationCancelled?.invoke()
            }
        )

        runCatching {
            windowManager.addView(root, params)
            windowManager.addView(bar.first, bar.second)
            calibrationView = root
            calibrationViewExtra = bar.first
        }
    }

    private var calibrationViewExtra: View? = null
    private var regionRect: View? = null

    /** Draggable/resizable rectangle for OCR regions. */
    @SuppressLint("ClickableViewAccessibility")
    private fun showRegionSelector() {
        val metrics = ScreenMetricsProvider.current()
        val rect = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#3300E676"))
                setStroke(dp(2), Color.parseColor("#00E676"))
            }
        }
        val handleSize = dp(28)
        // Corner handles.
        val handles = listOf(
            Gravity.TOP or Gravity.START, Gravity.TOP or Gravity.END,
            Gravity.BOTTOM or Gravity.START, Gravity.BOTTOM or Gravity.END
        ).map { g ->
            View(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(Color.parseColor("#00E676"))
                }
            }.also { h ->
                val lp = FrameLayout.LayoutParams(handleSize, handleSize, g)
                rect.addView(h, lp)
            }
        }

        val startW = min(metrics.widthPx - dp(40), dp(260))
        val startH = dp(90)
        val params = WindowManager.LayoutParams(
            startW, startH, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (metrics.widthPx - startW) / 2
            y = metrics.heightPx / 2 - startH / 2
        }

        fun applyMove(mode: Int, dx: Int, dy: Int) {
            val maxW = metrics.widthPx
            val maxH = metrics.heightPx
            when (mode) {
                0 -> { // move body
                    params.x = (params.x + dx).coerceIn(0, max(0, maxW - params.width))
                    params.y = (params.y + dy).coerceIn(0, max(0, maxH - params.height))
                }
                1 -> { // top-left
                    val nx = (params.x + dx).coerceIn(0, params.x + params.width - dp(30))
                    val ny = (params.y + dy).coerceIn(0, params.y + params.height - dp(30))
                    params.width += params.x - nx
                    params.height += params.y - ny
                    params.x = nx
                    params.y = ny
                }
                2 -> { // top-right
                    val ny = (params.y + dy).coerceIn(0, params.y + params.height - dp(30))
                    params.width = (params.width + dx).coerceAtLeast(dp(30))
                    params.height += params.y - ny
                    params.y = ny
                }
                3 -> { // bottom-left
                    val nx = (params.x + dx).coerceIn(0, params.x + params.width - dp(30))
                    params.width += params.x - nx
                    params.x = nx
                    params.height = (params.height + dy).coerceAtLeast(dp(30))
                }
                else -> { // bottom-right
                    params.width = (params.width + dx).coerceAtLeast(dp(30))
                    params.height = (params.height + dy).coerceAtLeast(dp(30))
                }
            }
            params.width = params.width.coerceAtMost(maxW)
            params.height = params.height.coerceAtMost(maxH)
            runCatching { windowManager.updateViewLayout(rect, params) }
        }

        // A single listener on the rect decides, at touch-down, whether the finger
        // grabbed a corner handle (resize) or the body (move). Per-child listeners
        // would shadow the body listener entirely.
        run {
            val hit = dp(44) // generous touch slop around each handle
            var activeMode = 0
            var lastX = 0f
            var lastY = 0f
            rect.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val w = params.width
                        val h = params.height
                        val x = e.x
                        val y = e.y
                        val nearLeft = x < hit
                        val nearRight = x > w - hit
                        val nearTop = y < hit
                        val nearBottom = y > h - hit
                        activeMode = when {
                            nearTop && nearLeft -> 1
                            nearTop && nearRight -> 2
                            nearBottom && nearLeft -> 3
                            nearBottom && nearRight -> 4
                            else -> 0
                        }
                        lastX = e.rawX
                        lastY = e.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - lastX).toInt()
                        val dy = (e.rawY - lastY).toInt()
                        if (dx != 0 || dy != 0) {
                            lastX = e.rawX
                            lastY = e.rawY
                            applyMove(activeMode, dx, dy)
                        }
                        true
                    }
                    else -> true
                }
            }
        }

        val bar = buildCalibrationBar(
            hint = "拖动矩形框或四角，框住要识别的文字",
            onConfirm = {
                // The overlay is positioned in the USABLE area (below the status bar), while
                // selection coordinates must be in the full display frame. Use the real
                // status-bar height here rather than assuming zero.
                val offsetY = ScreenMetricsProvider.systemBarInsets().top
                val offsetX = ScreenMetricsProvider.systemBarInsets().left
                val l = params.x + offsetX
                val t = params.y + offsetY
                val r = params.x + params.width + offsetX
                val b = params.y + params.height + offsetY
                hideCalibration()
                onRegionPicked?.invoke(l, t, r, b)
            },
            onCancel = {
                hideCalibration()
                onCalibrationCancelled?.invoke()
            }
        )

        runCatching {
            windowManager.addView(rect, params)
            windowManager.addView(bar.first, bar.second)
            calibrationView = rect
            calibrationViewExtra = bar.first
            regionRect = rect
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(target: View, onDrag: (Int, Int) -> Unit) {
        var lastX = 0f
        var lastY = 0f
        target.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.rawX; lastY = e.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - lastX).toInt()
                    val dy = (e.rawY - lastY).toInt()
                    if (dx != 0 || dy != 0) {
                        lastX = e.rawX; lastY = e.rawY
                        onDrag(dx, dy)
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun buildCalibrationBar(
        hint: String,
        onConfirm: () -> Unit,
        onCancel: () -> Unit
    ): Pair<View, WindowManager.LayoutParams> {
        val metrics = ScreenMetricsProvider.current()
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#F2202124"))
                setStroke(dp(1), Color.parseColor("#55FFFFFF"))
            }
        }
        bar.addView(TextView(context).apply {
            text = hint
            setTextColor(Color.WHITE)
            textSize = 13f
        })
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(makeButton("确认位置") { onConfirm() }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(makeButton("取消") { onCancel() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        bar.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        val params = WindowManager.LayoutParams(
            min(metrics.widthPx - dp(24), dp(360)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(60)
        }
        return Pair(bar, params)
    }

    fun removeAll() {
        hidePanel()
        hideCalibration()
        feedbackViews.forEach { runCatching { windowManager.removeView(it) } }
        feedbackViews.clear()
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
        bubbleStatusView = null
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}

/** Bridges engine status to the overlay without a service dependency cycle. */
object AutomationServiceStatusHolder {
    @Volatile
    private var status: EngineStatus = EngineStatus()
    fun update(value: EngineStatus) { status = value }
    fun current(): EngineStatus = status
}
