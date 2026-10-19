package io.github.shimo.smartclickocr.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.shimo.smartclickocr.R
import io.github.shimo.smartclickocr.data.AutomationProfile
import io.github.shimo.smartclickocr.data.AutomationRepository
import io.github.shimo.smartclickocr.model.ActionType
import io.github.shimo.smartclickocr.model.AutomationAction
import io.github.shimo.smartclickocr.model.ScreenMeta
import io.github.shimo.smartclickocr.ocr.OcrMatcher
import io.github.shimo.smartclickocr.service.AutomationAccessibilityService
import io.github.shimo.smartclickocr.service.AutomationService
import io.github.shimo.smartclickocr.util.CoordinateMapper
import io.github.shimo.smartclickocr.util.ScreenMetricsProvider
import kotlinx.coroutines.launch

/**
 * Edits one action. Positions are set visually (crosshair / region box); the raw
 * X/Y numbers are shown as advanced information only (spec §7.2 / §9.2).
 */
class ActionEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_ACTION_ID = "action_id"
    }

    /** Static hand-off used by the overlay callbacks. */
    object PendingResults {
        val points = mutableMapOf<String, Pair<Float, Float>>()
        val regions = mutableMapOf<String, IntArray>()
        val swipeEnds = mutableMapOf<String, Pair<Float, Float>>()
    }

    private lateinit var repository: AutomationRepository
    private var profileId: String? = null
    private var actionId: String? = null
    private var profile: AutomationProfile? = null
    private var action: AutomationAction? = null

    private lateinit var content: LinearLayout
    private lateinit var delayInput: EditText
    private lateinit var waitInput: EditText
    private lateinit var durationInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScreenMetricsProvider.attach(this)
        repository = AutomationRepository(this)
        profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
        actionId = intent.getStringExtra(EXTRA_ACTION_ID)

        profile = repository.load().profiles.firstOrNull { it.id == profileId }
        action = profile?.actions?.firstOrNull { it.id == actionId }
        if (profile == null || action == null) {
            Toast.makeText(this, "动作不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        buildUi()
        applyPendingResults()
    }

    override fun onResume() {
        super.onResume()
        applyPendingResults()
    }

    // -------------------------------------------------------------- results

    /** Pulls point/region results produced by calibration overlays. */
    private fun applyPendingResults() {
        val id = actionId ?: return
        val current = action ?: return
        var updated = current
        PendingResults.points.remove(id)?.let { (x, y) ->
            updated = updated.copy(x = x, y = y, screenMeta = ScreenMetricsProvider.current())
        }
        PendingResults.swipeEnds.remove(id)?.let { (x, y) ->
            updated = updated.copy(endX = x, endY = y, screenMeta = ScreenMetricsProvider.current())
        }
        PendingResults.regions.remove(id)?.let { r ->
            updated = updated.copy(
                regionLeft = r[0], regionTop = r[1], regionRight = r[2], regionBottom = r[3],
                screenMeta = ScreenMetricsProvider.current()
            )
        }
        if (updated != current) {
            action = updated
            saveToProfile(silent = true)
            renderPointSection()
            renderRegionSection()
        }
    }

    // ------------------------------------------------------------------- UI

    private fun buildUi() {
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val scroll = android.widget.ScrollView(this).apply { addView(content) }
        setContentView(scroll)

        val a = action!!
        content.addView(TextView(this).apply {
            text = "编辑动作：${a.type.displayName}"
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(12))
        })

        content.addView(label("执行前延迟（毫秒）"))
        delayInput = numberInput(a.delayBeforeMs.toString()) { value ->
            action = action?.copy(delayBeforeMs = value.coerceAtLeast(0))
            saveToProfile(silent = true)
        }
        content.addView(delayInput)
        content.addView(hint(secondsHint(a.delayBeforeMs)))

        when (a.type) {
            ActionType.CLICK -> buildClickSection()
            ActionType.SWIPE -> buildSwipeSection()
            ActionType.WAIT -> buildWaitSection()
            ActionType.OCR_CHECK, ActionType.STOP_IF -> buildOcrSection()
            ActionType.BACK -> content.addView(hint("返回动作无需其他参数，执行 Android 返回键。"))
        }

        content.addView(Button(this).apply {
            text = "完成并返回"
            isAllCaps = false
            setOnClickListener {
                saveToProfile()
                finish()
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) }
        })
    }

    private var pointSection: LinearLayout? = null
    private var regionSection: LinearLayout? = null

    // ---------------------------------------------------------------- CLICK

    private fun buildClickSection() {
        pointSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(pointSection)
        renderPointSection()

        content.addView(button("选择位置（准星）") { startPointCalibration() })
        content.addView(button("直接点选屏幕（可选）") { startDirectPick() })
        content.addView(button("手动输入坐标") { promptManualPoint() })
        content.addView(button("测试点击") { testClick() })
    }

    private fun renderPointSection() {
        val section = pointSection ?: return
        section.removeAllViews()
        val a = action ?: return
        section.addView(label("点击位置"))
        if (!a.isClickConfigured) {
            section.addView(
                hint("尚未设置位置。可以用「选择位置」拖动准星确认，也可以「手动输入坐标」直接填数字。")
            )
        } else {
            section.addView(hint("坐标：X = ${a.x.toInt()}，Y = ${a.y.toInt()}"))
            if (a.screenMeta.isValid) {
                val current = ScreenMetricsProvider.current()
                if (CoordinateMapper.needsRecalibration(a.screenMeta, current)) {
                    // A manually entered point may intentionally target another device,
                    // so this is a notice rather than an error.
                    section.addView(
                        hint(
                            CoordinateMapper.describeRecalibration(a.screenMeta, current) +
                                "\n如果这是在别的手机上录入的坐标，可以忽略此提示。",
                            isWarn = true
                        )
                    )
                }
            }
            val preview = PointPreviewView(this).apply {
                setPoint(a.x, a.y)
                layoutParams = LinearLayout.LayoutParams(-1, dp(140)).apply { topMargin = dp(6) }
            }
            section.addView(preview)
        }
    }

    /**
     * Manual X/Y entry, so a profile authored on one phone can be reproduced on
     * another by typing the same numbers (and so exact values can be fine-tuned).
     */
    private fun promptManualPoint() {
        val a = action ?: return
        val current = ScreenMetricsProvider.current()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val xInput = EditText(this).apply {
            hint = "X"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(if (a.x >= 0f) a.x.toInt().toString() else "")
        }
        val yInput = EditText(this).apply {
            hint = "Y"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(if (a.y >= 0f) a.y.toInt().toString() else "")
        }
        row.addView(xInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(yInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        wrapper.addView(
            TextView(this).apply {
                text = if (current.isValid) {
                    "当前屏幕：${current.widthPx} × ${current.heightPx}\n" +
                        "坐标从屏幕左上角算起（含状态栏区域），单位是像素。"
                } else {
                    "坐标从屏幕左上角算起，单位是像素。"
                }
                textSize = 12f
                setTextColor(android.graphics.Color.parseColor("#757575"))
                setPadding(dp(20), 0, dp(20), 0)
            }
        )
        wrapper.addView(row)

        AlertDialog.Builder(this)
            .setTitle("手动输入点击坐标")
            .setView(wrapper)
            .setPositiveButton("确定") { _, _ ->
                val nx = xInput.text.toString().toFloatOrNull()
                val ny = yInput.text.toString().toFloatOrNull()
                if (nx == null || ny == null || nx < 0f || ny < 0f) {
                    Toast.makeText(this, "请输入合法的非负坐标", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (current.isValid && (nx >= current.widthPx || ny >= current.heightPx)) {
                    Toast.makeText(
                        this,
                        "坐标超出当前屏幕范围（${current.widthPx} × ${current.heightPx}），已保存但可能点不到",
                        Toast.LENGTH_LONG
                    ).show()
                }
                // screenMeta records which screen the numbers describe; recalibration
                // warnings are informational because cross-device reuse is expected.
                action = action?.copy(
                    x = nx, y = ny, screenMeta = ScreenMetricsProvider.current()
                )
                saveToProfile(silent = true)
                renderPointSection()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------------------------------------------------------- SWIPE

    private val swipeSection: LinearLayout by lazy {
        LinearLayout(this).also { it.orientation = LinearLayout.VERTICAL }
    }

    private fun buildSwipeSection() {
        content.addView(swipeSection)
        renderSwipeSection()
        content.addView(button("选择起点（准星）") { startPointCalibration(end = false) })
        content.addView(button("选择终点（准星）") { startPointCalibration(end = true) })
        content.addView(button("手动输入坐标") { promptManualSwipe() })
        content.addView(label("滑动时长（毫秒，必须大于 0）"))
        durationInput = numberInput((action?.durationMs ?: 300L).toString()) { value ->
            action = action?.copy(durationMs = value.coerceAtLeast(1))
            saveToProfile(silent = true)
        }
        content.addView(durationInput)
        content.addView(button("测试滑动") { testSwipe() })
    }

    /** Manual start/end entry for swipes (cross-device reuse, exact values). */
    private fun promptManualSwipe() {
        val a = action ?: return
        val current = ScreenMetricsProvider.current()
        fun numField(value: Float, label: String): EditText = EditText(this).apply {
            hint = label
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(if (value >= 0f) value.toInt().toString() else "")
        }

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        wrapper.addView(
            TextView(this).apply {
                text = if (current.isValid) {
                    "当前屏幕：${current.widthPx} × ${current.heightPx}，单位像素，原点在左上角。"
                } else {
                    "单位像素，原点在屏幕左上角。"
                }
                textSize = 12f
                setTextColor(android.graphics.Color.parseColor("#757575"))
                setPadding(dp(20), 0, dp(20), dp(4))
            }
        )

        val startRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val sxInput = numField(a.x, "起点X")
        val syInput = numField(a.y, "起点Y")
        startRow.addView(sxInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        startRow.addView(syInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val endRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val exInput = numField(a.endX, "终点X")
        val eyInput = numField(a.endY, "终点Y")
        endRow.addView(exInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        endRow.addView(eyInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        wrapper.addView(startRow)
        wrapper.addView(endRow)

        AlertDialog.Builder(this)
            .setTitle("手动输入滑动坐标")
            .setView(wrapper)
            .setPositiveButton("确定") { _, _ ->
                val sx = sxInput.text.toString().toFloatOrNull()
                val sy = syInput.text.toString().toFloatOrNull()
                val ex = exInput.text.toString().toFloatOrNull()
                val ey = eyInput.text.toString().toFloatOrNull()
                if (sx == null || sy == null || ex == null || ey == null || sx < 0f || sy < 0f || ex < 0f || ey < 0f) {
                    Toast.makeText(this, "请输入合法的非负坐标", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                action = action?.copy(
                    x = sx, y = sy, endX = ex, endY = ey,
                    screenMeta = ScreenMetricsProvider.current()
                )
                saveToProfile(silent = true)
                renderSwipeSection()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun renderSwipeSection() {
        swipeSection.removeAllViews()
        val a = action ?: return
        swipeSection.addView(label("起点 / 终点"))
        if (!a.isSwipeConfigured) {
            swipeSection.addView(hint("请分别选择起点和终点，无需手输坐标。"))
        } else {
            swipeSection.addView(hint("起点 (${a.x.toInt()}, ${a.y.toInt()})  →  终点 (${a.endX.toInt()}, ${a.endY.toInt()})"))
            swipeSection.addView(PointPreviewView(this).apply {
                setSwipe(a.x, a.y, a.endX, a.endY)
                layoutParams = LinearLayout.LayoutParams(-1, dp(140)).apply { topMargin = dp(6) }
            })
        }
    }

    // ----------------------------------------------------------------- WAIT

    private fun buildWaitSection() {
        content.addView(label("等待时长（毫秒）"))
        waitInput = numberInput((action?.waitDurationMs ?: 1000L).toString()) { value ->
            action = action?.copy(waitDurationMs = value.coerceAtLeast(0))
            saveToProfile(silent = true)
        }
        content.addView(waitInput)
        content.addView(hint("这是动作本身的等待时长，与上面的「执行前延迟」相互独立。"))
    }

    // ------------------------------------------------------------------ OCR

    private fun buildOcrSection() {
        if (action?.type == ActionType.STOP_IF) {
            content.addView(
                hint(
                    "这个动作只做判断：框选的区域里出现目标文字（完全匹配）时，" +
                        "整个任务立即停止；没出现就继续执行后面的动作。",
                    isWarn = false
                )
            )
        }
        content.addView(label("目标文字（完全匹配）"))
        val targetInput = EditText(this).apply {
            setText(action?.targetText ?: "")
            hint = "例如：任务完成"
            inputType = InputType.TYPE_CLASS_TEXT
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    action = action?.copy(targetText = s?.toString() ?: "")
                    saveToProfile(silent = true)
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            })
        }
        content.addView(targetInput)
        content.addView(hint("识别文本与目标文字会去掉首尾空白后逐字比较；「任务完成」不会匹配「任务已完成」。"))

        regionSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(regionSection)
        renderRegionSection()

        content.addView(button("框选识别区域") { startRegionCalibration() })
        content.addView(button("手动输入区域") { promptManualRegion() })
        content.addView(button("测试 OCR") { testOcr() })

        val resultView = TextView(this).apply {
            id = View.generateViewId()
            tag = "ocr_result"
            textSize = 13f
            setPadding(0, dp(8), 0, dp(8))
        }
        content.addView(resultView)
    }

    private fun renderRegionSection() {
        val section = regionSection ?: return
        section.removeAllViews()
        val a = action ?: return
        section.addView(label("识别区域"))
        if (!a.isRegionConfigured) {
            section.addView(
                hint(
                    "尚未框选区域。请点击「框选识别区域」拖动矩形框住目标文字。\n" +
                        "提示：区域不要框得太紧——只框住单个字时识别引擎容易判错，" +
                        "建议把该字所在的整行（或周边一小圈）一起框进来。"
                )
            )
        } else {
            section.addView(hint("区域：(${a.regionLeft}, ${a.regionTop}) - (${a.regionRight}, ${a.regionBottom})  宽 ${a.regionWidth} × 高 ${a.regionHeight}"))
            // A too-small selection is the most common reason a visible character is not
            // recognised: the detector needs some surrounding context.
            val smallestSide = minOf(a.regionWidth, a.regionHeight)
            if (smallestSide in 1..60 || a.regionWidth < 120) {
                section.addView(
                    hint(
                        "区域偏小（${a.regionWidth} × ${a.regionHeight}）。" +
                            "识别引擎对孤立的小区域准确率较低，建议放大到整行或字周围一圈后重新框选。",
                        isWarn = true
                    )
                )
            }
            if (a.screenMeta.isValid) {
                val current = ScreenMetricsProvider.current()
                if (CoordinateMapper.needsRecalibration(a.screenMeta, current)) {
                    section.addView(hint(CoordinateMapper.describeRecalibration(a.screenMeta, current), isWarn = true))
                }
            }
            section.addView(RegionPreviewView(this).apply {
                setRegion(a.regionLeft, a.regionTop, a.regionRight, a.regionBottom)
                layoutParams = LinearLayout.LayoutParams(-1, dp(120)).apply { topMargin = dp(6) }
            })
            if (a.regionWidth <= 0 || a.regionHeight <= 0) {
                section.addView(hint("区域宽高必须大于 0，请重新框选", isWarn = true))
            }
        }
    }

    // ------------------------------------------------------------ calibration

    private fun requireOverlay(): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限，才能在其他应用上选点", Toast.LENGTH_LONG).show()
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            return false
        }
        return true
    }

    private fun startPointCalibration(end: Boolean = false) {
        if (!requireOverlay()) return
        val id = actionId ?: return
        val service = requireService() ?: return
        // Register a one-shot callback through the service overlay.
        service.onPointPicked = { x, y ->
            if (end) {
                action = action?.copy(endX = x, endY = y, screenMeta = ScreenMetricsProvider.current())
            } else {
                action = action?.copy(x = x, y = y, screenMeta = ScreenMetricsProvider.current())
            }
            service.onPointPicked = null
            saveToProfile(silent = true)
            runOnUiThread {
                renderPointSection()
                renderSwipeSection()
                Toast.makeText(this, "位置已确认：(${x.toInt()}, ${y.toInt()})", Toast.LENGTH_SHORT).show()
            }
        }
        service.onCalibrationCancelled = {
            service.onPointPicked = null
            service.onRegionPicked = null
            service.onCalibrationCancelled = null
        }
        service.showOverlay(io.github.shimo.smartclickocr.overlay.OverlayController.Mode.PICK_POINT)
        // Let the user see the target app underneath.
        moveTaskToBack(true)
    }

    private fun startRegionCalibration() {
        if (!requireOverlay()) return
        val service = requireService() ?: return
        service.onRegionPicked = { l, t, r, b ->
            action = action?.copy(
                regionLeft = l, regionTop = t, regionRight = r, regionBottom = b,
                screenMeta = ScreenMetricsProvider.current()
            )
            service.onRegionPicked = null
            saveToProfile(silent = true)
            runOnUiThread {
                renderRegionSection()
                Toast.makeText(this, "区域已确认：${r - l} × ${b - t}", Toast.LENGTH_SHORT).show()
            }
        }
        service.onCalibrationCancelled = {
            service.onRegionPicked = null
            service.onCalibrationCancelled = null
        }
        service.showOverlay(io.github.shimo.smartclickocr.overlay.OverlayController.Mode.PICK_REGION)
        moveTaskToBack(true)
    }

    /**
     * "Direct on-screen pick" alternative (spec §7.2.2). Android does not let an
     * ordinary overlay transparently capture and then swallow a touch that the app
     * underneath should not receive, so this opens the system touch-location
     * developer option if available and otherwise explains the crosshair path.
     */
    private fun startDirectPick() {
        android.app.AlertDialog.Builder(this)
            .setTitle("直接点选屏幕（可选）")
            .setMessage(
                "Android 不允许普通悬浮层截获一次触摸后再阻止目标应用接收它，因此本应用默认使用准星选点，" +
                    "保证校准过程绝不会误触目标按钮。\n\n你可以打开系统的「开发者选项 → 指针位置」，" +
                    "直接点按屏幕查看坐标，再回到这里用准星微调；坐标数值也会实时显示。"
            )
            .setPositiveButton("已知晓，使用准星") { _, _ -> startPointCalibration() }
            .setNegativeButton("关闭", null)
            .show()
    }

    // -------------------------------------------------------------- testing

    private fun testClick() {
        val a = action ?: return
        if (!a.isClickConfigured) {
            Toast.makeText(this, "请先选择点击位置", Toast.LENGTH_SHORT).show()
            return
        }
        if (!AutomationAccessibilityService.isConnected()) {
            Toast.makeText(this, "无障碍服务未启用，无法派发点击", Toast.LENGTH_LONG).show()
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("测试点击")
            .setMessage("将向坐标 (${a.x.toInt()}, ${a.y.toInt()}) 真实派发一次点击，可能触发目标应用中的按钮。确定继续？")
            .setPositiveButton("确定点击") { _, _ ->
                lifecycleScope.launch {
                    val error = AutomationAccessibilityService.instance?.testTap(a.x, a.y)
                    Toast.makeText(
                        this@ActionEditorActivity,
                        if (error == null) "已派发点击 (${a.x.toInt()}, ${a.y.toInt()})" else "点击失败：$error",
                        Toast.LENGTH_LONG
                    ).show()
                    // Visual feedback through the service overlay, if available.
                    AutomationService.instance?.showTapFeedback(a.x, a.y)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun testSwipe() {
        val a = action ?: return
        if (!a.isSwipeConfigured) {
            Toast.makeText(this, "请先选择起点和终点", Toast.LENGTH_SHORT).show()
            return
        }
        if (!AutomationAccessibilityService.isConnected()) {
            Toast.makeText(this, "无障碍服务未启用，无法派发滑动", Toast.LENGTH_LONG).show()
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("测试滑动")
            .setMessage("将从 (${a.x.toInt()}, ${a.y.toInt()}) 滑动到 (${a.endX.toInt()}, ${a.endY.toInt()})。确定继续？")
            .setPositiveButton("确定滑动") { _, _ ->
                lifecycleScope.launch {
                    val error = AutomationAccessibilityService.instance
                        ?.testSwipe(a.x, a.y, a.endX, a.endY, a.durationMs)
                    Toast.makeText(
                        this@ActionEditorActivity,
                        if (error == null) "滑动已派发" else "滑动失败：$error",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** Manual left/top/right/bottom entry for OCR regions (cross-device reuse). */
    private fun promptManualRegion() {
        val a = action ?: return
        val current = ScreenMetricsProvider.current()
        fun numField(value: Int, label: String): EditText = EditText(this).apply {
            hint = label
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(if (value > 0) value.toString() else "")
        }

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        wrapper.addView(
            TextView(this).apply {
                text = if (current.isValid) {
                    "当前屏幕：${current.widthPx} × ${current.heightPx}，单位像素。\n左<右、上<下才会被接受。"
                } else {
                    "单位像素。左<右、上<下才会被接受。"
                }
                textSize = 12f
                setTextColor(android.graphics.Color.parseColor("#757575"))
                setPadding(dp(20), 0, dp(20), dp(4))
            }
        )

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val lInput = numField(a.regionLeft, "左")
        val tInput = numField(a.regionTop, "上")
        row1.addView(lInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(tInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val rInput = numField(a.regionRight, "右")
        val bInput = numField(a.regionBottom, "下")
        row2.addView(rInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row2.addView(bInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        wrapper.addView(row1)
        wrapper.addView(row2)

        AlertDialog.Builder(this)
            .setTitle("手动输入识别区域")
            .setView(wrapper)
            .setPositiveButton("确定") { _, _ ->
                val l = lInput.text.toString().toIntOrNull()
                val t = tInput.text.toString().toIntOrNull()
                val r = rInput.text.toString().toIntOrNull()
                val b = bInput.text.toString().toIntOrNull()
                if (l == null || t == null || r == null || b == null) {
                    Toast.makeText(this, "请填写四个边界值", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (r <= l || b <= t || l < 0 || t < 0) {
                    Toast.makeText(this, "区域无效：需要 左<右、上<下 且不能为负", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                action = action?.copy(
                    regionLeft = l, regionTop = t, regionRight = r, regionBottom = b,
                    screenMeta = ScreenMetricsProvider.current()
                )
                saveToProfile(silent = true)
                renderRegionSection()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun testOcr() {
        val a = action ?: return
        if (!a.isRegionConfigured) {
            Toast.makeText(this, "请先框选识别区域", Toast.LENGTH_SHORT).show()
            return
        }
        val service = AutomationService.instance
        if (service == null || !service.hasCaptureSession()) {
            Toast.makeText(this, "屏幕捕获未授权：请回到主界面，点击「屏幕截图」授权后再测试", Toast.LENGTH_LONG).show()
            return
        }
        val resultView = content.findViewWithTag<TextView>("ocr_result")
        resultView?.text = "识别中…"
        lifecycleScope.launch {
            val (text, error) = service.testOcr(a)
            resultView?.text = if (error != null) {
                "OCR 失败：$error"
            } else {
                val recognised = text ?: ""
                val shown = recognised.trim().ifEmpty { "（未识别到文字）" }.replace("\n", " ⏎ ")
                // With no target text there is nothing to compare against; saying
                // "matched" here was misleading.
                val verdict = if (a.targetText.isBlank()) {
                    "（未填写目标文字，无法判断）"
                } else {
                    // Same rule as the engine: any single recognised block may match.
                    val matched = OcrMatcher.isExactMatch(recognised, a.targetText) ||
                        recognised.split('\n').any {
                            OcrMatcher.isExactMatch(it, a.targetText)
                        }
                    if (matched) "✅ 完全匹配" else "❌ 不匹配"
                }
                // Show the geometry the crop used, so a coordinate mismatch is visible.
                val diag = service.lastOcrDiagnostics()
                "最近识别：$shown\n目标文字：${a.targetText.ifBlank { "（未填写）" }}\n匹配结果：$verdict" +
                    if (diag.isNotBlank()) "\n\n$diag" else ""
            }
        }
    }

    // ------------------------------------------------------------- helpers

    /** Returns the live service, starting it if needed; shows a toast and returns null on failure. */
    private fun requireService(): AutomationService? {
        AutomationService.instance?.let { return it }
        return try {
            val started = AutomationService.ensureStarted(this)
            if (started == null) {
                Toast.makeText(this, "后台服务启动中，请稍后重试", Toast.LENGTH_SHORT).show()
            }
            started
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "无法启动后台服务", Toast.LENGTH_LONG).show()
            null
        }
    }

    private fun saveToProfile(silent: Boolean = false) {
        val currentProfile = profile ?: return
        val updated = action ?: return
        val newProfile = currentProfile.copy(
            actions = currentProfile.actions.map { if (it.id == updated.id) updated.normalized() else it },
            updatedAt = System.currentTimeMillis()
        )
        profile = newProfile
        action = newProfile.actions.firstOrNull { it.id == updated.id }
        // Persist by replacing the whole profile in the store.
        val snapshot = repository.load()
        val profiles = snapshot.profiles.map { if (it.id == newProfile.id) newProfile else it }
        repository.save(
            AutomationRepository.Snapshot(profiles, snapshot.selectedProfileId, snapshot.floatingX, snapshot.floatingY)
        )
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(12), 0, dp(4))
    }

    private fun hint(text: String, isWarn: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(if (isWarn) Color.parseColor("#C62828") else Color.parseColor("#757575"))
        setPadding(0, dp(2), 0, dp(4))
    }

    private fun secondsHint(ms: Long): String = "约 ${String.format("%.2f", ms / 1000.0)} 秒；不能为负数"

    private fun numberInput(initial: String, onChange: (Long) -> Unit): EditText =
        EditText(this).apply {
            setText(initial)
            inputType = InputType.TYPE_CLASS_NUMBER
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    val value = s?.toString()?.toLongOrNull() ?: return
                    onChange(value)
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            })
        }

    private fun button(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
