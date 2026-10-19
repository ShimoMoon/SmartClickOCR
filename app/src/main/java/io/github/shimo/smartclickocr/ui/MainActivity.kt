package io.github.shimo.smartclickocr.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.shimo.smartclickocr.R
import io.github.shimo.smartclickocr.data.AutomationProfile
import io.github.shimo.smartclickocr.data.AutomationRepository
import io.github.shimo.smartclickocr.data.ProfileCodec
import io.github.shimo.smartclickocr.model.ActionType
import io.github.shimo.smartclickocr.model.AutomationAction
import io.github.shimo.smartclickocr.model.EngineState
import io.github.shimo.smartclickocr.model.EngineStatus
import io.github.shimo.smartclickocr.service.AutomationAccessibilityService
import io.github.shimo.smartclickocr.service.AutomationService
import io.github.shimo.smartclickocr.util.RunLog
import io.github.shimo.smartclickocr.util.ScreenMetricsProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Main screen: permission guidance, profile management, the action list and
 * start/pause/resume/stop controls (spec §9.1).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var repository: AutomationRepository
    private var snapshotFloatingX = -1
    private var snapshotFloatingY = -1
    private var profiles: List<AutomationProfile> = emptyList()
    private var selectedId: String? = null

    private lateinit var profileSpinner: TextView
    private lateinit var actionList: LinearLayout
    private lateinit var emptyHint: TextView
    private lateinit var statusText: TextView
    private lateinit var progressText: TextView
    private lateinit var logText: TextView
    private lateinit var permAccessibility: TextView
    private lateinit var permOverlay: TextView
    private lateinit var permCapture: TextView
    private lateinit var permBattery: TextView
    private lateinit var startButton: Button
    private lateinit var pauseButton: Button
    private lateinit var resumeButton: Button
    private lateinit var stopButton: Button

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val data = result.data!!
            val resultCode = result.resultCode
            // The service may have been started but not yet created; retry once it is ready.
            val service = AutomationService.instance
            if (service != null) {
                applyCaptureResult(service, resultCode, data)
            } else {
                pendingCapture = Pair(resultCode, data)
                Toast.makeText(this, "正在启动后台服务…", Toast.LENGTH_SHORT).show()
                ensureServiceStarted()
            }
            refreshPermissions()
        } else {
            pendingCapture = null
            Toast.makeText(this, "未授予屏幕截图权限，OCR 动作将无法运行", Toast.LENGTH_LONG).show()
            refreshPermissions()
        }
    }

    private var pendingCapture: Pair<Int, Intent>? = null

    private fun applyCaptureResult(service: AutomationService, resultCode: Int, data: Intent) {
        val error = service.startCapture(resultCode, data)
        if (error != null) {
            // Show the concrete reason and how to retry; a Toast alone is easy to miss.
            AlertDialog.Builder(this)
                .setTitle("无法创建屏幕捕获会话")
                .setMessage(
                    "$error\n\n" +
                        "常见原因与处理：\n" +
                        "1. 上一轮授权已被系统回收 —— 重新点「屏幕截图」再同意一次即可；\n" +
                        "2. 系统弹窗被拒绝或超时 —— 请在弹出的系统对话框上点「立即开始」；\n" +
                        "3. ColorOS 限制后台录制 —— 在「应用管理 → SmartClickOCR → 权限」里" +
                        "允许屏幕录制/显示在其他应用上层；\n" +
                        "4. 仍未解决可重启手机后重试。"
                )
                .setPositiveButton("重新授权") { _, _ -> requestCapture() }
                .setNegativeButton("稍后", null)
                .show()
        } else {
            Toast.makeText(this, "屏幕截图已授权", Toast.LENGTH_SHORT).show()
        }
        refreshPermissions()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScreenMetricsProvider.attach(this)
        repository = AutomationRepository(this)
        loadSnapshot()
        setContentView(buildLayout())
        observeState()
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        // The editor saves actions directly to the store, so re-read it here.
        loadSnapshot()
        refreshAll()
        startPermissionWatch()
    }

    override fun onPause() {
        stopPermissionWatch()
        super.onPause()
    }

    private var permWatchJob: Job? = null

    /**
     * Polls the permission rows twice a second while visible.
     *
     * Enabling accessibility (or returning from the system settings) settles
     * asynchronously, so a single refresh on resume left the row showing 未授权 even
     * though the service was already bound.
     */
    private fun startPermissionWatch() {
        if (permWatchJob?.isActive == true) return
        permWatchJob = lifecycleScope.launch {
            while (isActive) {
                refreshPermissions()
                delay(500)
            }
        }
    }

    private fun stopPermissionWatch() {
        permWatchJob?.cancel()
        permWatchJob = null
    }

    // ------------------------------------------------------------ persistence

    private fun loadSnapshot() {
        var snapshot = repository.load()
        if (snapshot.profiles.isEmpty()) {
            snapshot = repository.createDefaultSnapshot()
            repository.save(snapshot)
        }
        profiles = snapshot.profiles
        selectedId = snapshot.selectedProfileId?.takeIf { id -> profiles.any { it.id == id } }
            ?: profiles.first().id
        // Remember the floating-button position stored in the snapshot so a later
        // persist() does not wipe it (FloatingPositionStore owns the live value).
        snapshotFloatingX = snapshot.floatingX
        snapshotFloatingY = snapshot.floatingY
    }

    private fun persist() {
        repository.save(
            // Floating button position is owned by FloatingPositionStore; pass it through unchanged.
            AutomationRepository.Snapshot(profiles, selectedId, snapshotFloatingX, snapshotFloatingY)
        )
    }

    private fun currentProfile(): AutomationProfile? = profiles.firstOrNull { it.id == selectedId }

    private fun updateProfile(updated: AutomationProfile) {
        profiles = profiles.map { if (it.id == updated.id) updated.copy(updatedAt = System.currentTimeMillis()) else it }
        persist()
        refreshActions()
    }

    // ------------------------------------------------------------------- UI

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.card_bg))
        }
        val scrollContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Permission card
        val permCard = card("权限状态")
        permAccessibility = permRow(permCard, R.string.perm_accessibility) { openAccessibilitySettings() }
        permOverlay = permRow(permCard, R.string.perm_overlay) { openOverlaySettings() }
        permCapture = permRow(permCard, R.string.perm_capture) { requestCapture() }
        permBattery = permRow(permCard, R.string.perm_battery) { openBatterySettings() }
        scrollContent.addView(permCard)

        // Profile card
        val profileCard = card("方案")
        profileSpinner = TextView(this).apply {
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(8))
        }
        profileCard.addView(profileSpinner)
        val profileRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowButton(profileRow, "切换") { chooseProfile() }
        rowButton(profileRow, "新建") { createProfile() }
        rowButton(profileRow, "重命名") { renameProfile() }
        rowButton(profileRow, "复制") { copyProfile() }
        rowButton(profileRow, "删除") { deleteProfile() }
        profileCard.addView(profileRow)
        scrollContent.addView(profileCard)

        // Status card
        val statusCard = card("运行状态")
        statusText = TextView(this).apply { textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD) }
        progressText = TextView(this).apply { textSize = 13f; setPadding(0, dp(4), 0, 0) }
        statusCard.addView(statusText)
        statusCard.addView(progressText)
        val controlRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        startButton = smallButton("开始") { onStartClicked() }
        pauseButton = smallButton("暂停") { AutomationService.instance?.pauseAutomation() }
        resumeButton = smallButton("继续") { AutomationService.instance?.resumeAutomation() }
        stopButton = smallButton("停止") { AutomationService.instance?.stopAutomation() }
        // Equal-width cells inside the horizontal row.
        listOf(startButton, pauseButton, resumeButton, stopButton).forEachIndexed { i, b ->
            controlRow.addView(
                b,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) leftMargin = dp(4)
                    topMargin = dp(6)
                }
            )
        }
        statusCard.addView(controlRow)
        scrollContent.addView(statusCard)

        // Actions card
        val actionCard = card("动作列表")
        emptyHint = TextView(this).apply {
            text = "还没有动作，点击下方「新增动作」开始"
            setPadding(0, dp(8), 0, dp(8))
        }
        actionCard.addView(emptyHint)
        // A plain LinearLayout instead of a RecyclerView: a RecyclerView nested in a
        // ScrollView with WRAP_CONTENT mis-measures its children and only rendered the
        // first couple of rows. Action counts here are small, so recycling is pointless.
        actionList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        actionCard.addView(actionList)
        fullButton(actionCard, "新增动作") { chooseActionType() }
        scrollContent.addView(actionCard)

        // Log card
        val logCard = card("运行日志")
        logText = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, dp(4), 0, dp(4))
        }
        logCard.addView(logText)
        fullButton(logCard, "清空日志") { RunLog.clear(); observeLog() }
        scrollContent.addView(logCard)

        val scroll = android.widget.ScrollView(this).apply { addView(scrollContent) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun card(title: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(android.graphics.Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 12f
                setTextColor(android.graphics.Color.parseColor("#757575"))
            })
        }

    private fun permRow(parent: LinearLayout, @androidx.annotation.StringRes label: Int, onClick: () -> Unit): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val name = TextView(this).apply {
            text = getString(label)
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val state = TextView(this).apply { textSize = 14f }
        row.addView(name)
        row.addView(state)
        row.addView(Button(this).apply {
            text = getString(R.string.go_grant)
            isAllCaps = false
            textSize = 12f
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(10), dp(2), dp(10), dp(2))
            setOnClickListener { onClick() }
        })
        parent.addView(row)
        return state
    }

    /**
     * Compact button. It intentionally sets NO layoutParams: a child's width must be
     * chosen by the container it is added to. `width = 0` only works inside a row with
     * weight; in a vertical LinearLayout it collapses to zero width and looks unclickable.
     */
    private fun smallButton(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 13f
            minWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { onClick() }
        }

    /** Adds a compact button as an equal-width cell inside a horizontal row. */
    private fun rowButton(row: LinearLayout, text: String, onClick: () -> Unit) {
        val button = smallButton(text, onClick)
        row.addView(
            button,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(4)
                topMargin = dp(6)
            }
        )
    }

    /** Adds a compact button stacked in a vertical container, filling the width. */
    private fun fullButton(parent: LinearLayout, text: String, onClick: () -> Unit) {
        parent.addView(
            smallButton(text, onClick),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------- refresh

    private fun refreshAll() {
        profileSpinner.text = currentProfile()?.name ?: "（无方案）"
        refreshActions()
        refreshPermissions()
        renderStatus(AutomationService.state.value)
        observeLog()
    }

    private fun refreshActions() {
        val actions = currentProfile()?.actions ?: emptyList()
        actionList.removeAllViews()
        emptyHint.visibility = if (actions.isEmpty()) View.VISIBLE else View.GONE
        actionList.visibility = if (actions.isEmpty()) View.GONE else View.VISIBLE
        actions.forEachIndexed { index, action ->
            actionList.addView(buildActionRow(action, index, actions.size))
        }
    }

    /** One row per action: title, delay, then its edit/enable/reorder/delete controls. */
    private fun buildActionRow(action: AutomationAction, index: Int, total: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val title = TextView(this).apply {
            text = "${index + 1}. ${action.type.displayName}  ·  ${action.summary()}"
            textSize = 15f
            setTextColor(
                if (action.enabled) android.graphics.Color.parseColor("#212121")
                else android.graphics.Color.parseColor("#9E9E9E")
            )
        }
        val subtitle = TextView(this).apply {
            text = "执行前延迟 ${action.delayBeforeMs} ms" + if (!action.enabled) "  ·  已禁用" else ""
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#757575"))
        }
        row.addView(title)
        row.addView(subtitle)

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val buttons = listOf(
            "编辑" to { editAction(action.id) },
            (if (action.enabled) "禁用" else "启用") to { toggleAction(action.id) },
            "上移" to { moveAction(action.id, -1) },
            "下移" to { moveAction(action.id, +1) },
            "删除" to { deleteAction(action.id) }
        )
        buttons.forEachIndexed { i, (label, onClick) ->
            val b = smallButton(label, onClick)
            b.textSize = 12f
            b.isEnabled = when (label) {
                "上移" -> index > 0
                "下移" -> index < total - 1
                else -> true
            }
            controls.addView(
                b,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) leftMargin = dp(2)
                }
            )
        }
        row.addView(controls)
        return row
    }

    /** Re-reads permission state and re-renders; cheap enough to call on a timer. */
    private fun refreshPermissions() {
        val a11y = isAccessibilityEnabled()
        setPermState(permAccessibility, a11y)
        val overlayOk = Settings.canDrawOverlays(this)
        setPermState(permOverlay, overlayOk)
        val captureOk = AutomationService.instance?.hasCaptureSession() == true
        setPermState(permCapture, captureOk)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        setPermState(permBattery, pm.isIgnoringBatteryOptimizations(packageName), okLabel = "已忽略")
    }

    /**
     * Accessibility "granted" check.
     *
     * The live service instance appears asynchronously after the user enables the
     * service (or after this app process restarts), so relying on it made the row show
     * 未授权 while the service was in fact bound. The system setting is the source of
     * truth; the instance is only an additional confirmation.
     */
    private fun isAccessibilityEnabled(): Boolean {
        if (AutomationAccessibilityService.isConnected()) return true
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = "$packageName/${AutomationAccessibilityService::class.java.name}"
        val meShort = "$packageName/.service.AutomationAccessibilityService"
        return enabled.split(':').any { it.equals(me, true) || it.equals(meShort, true) }
    }

    private fun setPermState(view: TextView, ok: Boolean, okLabel: String = "已授权") {
        view.text = if (ok) okLabel else "未授权"
        view.setTextColor(
            ContextCompat.getColor(this, if (ok) R.color.ok else R.color.bad)
        )
    }

    private fun renderStatus(status: EngineStatus) {
        statusText.text = "状态：${status.state.displayName}"
        val parts = mutableListOf<String>()
        status.progressText().takeIf { it.isNotBlank() }?.let { parts.add(it) }
        status.actionLabel.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        status.errorMessage?.let { parts.add("错误：$it") } ?: status.message.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        progressText.text = parts.joinToString("\n")

        val running = status.state == EngineState.RUNNING
        val paused = status.state == EngineState.PAUSED
        startButton.isEnabled = !status.state.isActive
        pauseButton.isEnabled = running
        resumeButton.isEnabled = paused
        stopButton.isEnabled = status.state.isActive
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    AutomationService.state.collect { renderStatus(it) }
                }
                launch {
                    RunLog.lines.collect { observeLog() }
                }
                launch {
                    AutomationService.serviceReady.collect { ready ->
                        if (ready) {
                            // Apply a capture grant that arrived before onCreate finished.
                            pendingCapture?.let { (code, data) ->
                                pendingCapture = null
                                AutomationService.instance?.let { applyCaptureResult(it, code, data) }
                            }
                            // Launch the system grant dialog once the service is alive.
                            if (pendingCaptureRequest) {
                                pendingCaptureRequest = false
                                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                projectionLauncher.launch(manager.createScreenCaptureIntent())
                            }
                            refreshPermissions()
                        }
                    }
                }
            }
        }
    }

    private fun observeLog() {
        val recent = RunLog.recent(40)
        logText.text = if (recent.isEmpty()) "（暂无日志）" else
            recent.joinToString("\n") { "${it.time}  ${it.text}" }
    }

    // ------------------------------------------------------------- actions

    private fun chooseActionType() {
        val types = ActionType.entries.map { it.displayName }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("新增动作")
            .setItems(types) { _, which ->
                val type = ActionType.entries[which]
                val profile = currentProfile() ?: return@setItems
                val action = AutomationAction(type = type)
                updateProfile(profile.copy(actions = profile.actions + action))
                editAction(action.id)
            }
            .show()
    }

    private fun editAction(actionId: String) {
        val intent = Intent(this, ActionEditorActivity::class.java)
        intent.putExtra(ActionEditorActivity.EXTRA_PROFILE_ID, selectedId)
        intent.putExtra(ActionEditorActivity.EXTRA_ACTION_ID, actionId)
        startActivity(intent)
    }

    private fun deleteAction(actionId: String) {
        val profile = currentProfile() ?: return
        AlertDialog.Builder(this)
            .setTitle("删除动作")
            .setMessage("确定删除这个动作吗？")
            .setPositiveButton("删除") { _, _ ->
                updateProfile(profile.copy(actions = profile.actions.filterNot { it.id == actionId }))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun moveAction(actionId: String, delta: Int) {
        val profile = currentProfile() ?: return
        val list = profile.actions.toMutableList()
        val index = list.indexOfFirst { it.id == actionId }
        val target = index + delta
        if (index < 0 || target !in list.indices) return
        val item = list.removeAt(index)
        list.add(target, item)
        updateProfile(profile.copy(actions = list))
    }

    private fun toggleAction(actionId: String) {
        val profile = currentProfile() ?: return
        updateProfile(
            profile.copy(
                actions = profile.actions.map {
                    if (it.id == actionId) it.copy(enabled = !it.enabled) else it
                }
            )
        )
    }

    // ------------------------------------------------------------- profiles

    private fun chooseProfile() {
        if (profiles.isEmpty()) return
        val names = profiles.map { it.name }.toTypedArray()
        val checked = profiles.indexOfFirst { it.id == selectedId }
        AlertDialog.Builder(this)
            .setTitle("选择方案")
            .setSingleChoiceItems(names, checked) { dialog, which ->
                selectedId = profiles[which].id
                persist()
                refreshAll()
                dialog.dismiss()
            }
            .show()
    }

    private fun createProfile() {
        askText("新建方案", "方案名称", "方案 ${profiles.size + 1}") { name ->
            val profile = AutomationProfile(UUID.randomUUID().toString(), name)
            profiles = profiles + profile
            selectedId = profile.id
            persist()
            refreshAll()
        }
    }

    private fun renameProfile() {
        val profile = currentProfile() ?: return
        askText("重命名方案", "方案名称", profile.name) { name ->
            updateProfile(profile.copy(name = name))
            profileSpinner.text = name
        }
    }

    private fun copyProfile() {
        val profile = currentProfile() ?: return
        val copy = profile.copy(
            id = UUID.randomUUID().toString(),
            name = "${profile.name} 副本",
            actions = profile.actions.map {
                // New ids so both profiles stay independent.
                it.copy(id = UUID.randomUUID().toString())
            },
            updatedAt = System.currentTimeMillis()
        )
        profiles = profiles + copy
        selectedId = copy.id
        persist()
        refreshAll()
    }

    private fun deleteProfile() {
        val profile = currentProfile() ?: return
        if (profiles.size <= 1) {
            Toast.makeText(this, "至少保留一个方案", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("删除方案")
            .setMessage("确定删除「${profile.name}」及其全部动作吗？此操作不可撤销。")
            .setPositiveButton("删除") { _, _ ->
                profiles = profiles.filterNot { it.id == profile.id }
                selectedId = profiles.first().id
                persist()
                refreshAll()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun askText(title: String, hint: String, initial: String, onDone: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(dp(24), dp(12), dp(24), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotEmpty()) onDone(value)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------- running

    private fun onStartClicked() {
        val profile = currentProfile()
        if (profile == null) {
            Toast.makeText(this, "没有可用方案", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isAccessibilityEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("需要无障碍服务")
                .setMessage("自动化点击与滑动依赖无障碍服务，请先在系统设置中开启 SmartClickOCR 的「无障碍服务」。")
                .setPositiveButton("去设置") { _, _ -> openAccessibilitySettings() }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        // Both OCR_CHECK and STOP_IF read the screen, so either one needs the capture grant.
        val usesOcr = profile.actions.any { it.enabled && it.type.isOcrBased }
        val service = AutomationService.instance
        if (service == null) {
            // First start: the service is created asynchronously, so start it and let
            // the user press 开始 again once it is ready instead of failing silently.
            ensureServiceStarted()
            Toast.makeText(this, "后台服务启动中，请稍候再点「开始」", Toast.LENGTH_SHORT).show()
            return
        }
        if (usesOcr && !service.hasCaptureSession()) {
            AlertDialog.Builder(this)
                .setTitle("需要屏幕截图权限")
                .setMessage("本方案包含 OCR 动作，需要先授予屏幕截图权限，SmartClickOCR 用它来识别屏幕文字（仅在内存中处理，不保存、不上传）。")
                .setPositiveButton("去授权") { _, _ -> requestCapture() }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "未授予悬浮窗权限，将无法在其他应用上使用悬浮控制按钮", Toast.LENGTH_LONG).show()
        }
        // Arm the task instead of running it right away: the user still has to switch to
        // the target app and press 开始 on the floating panel. Running immediately would
        // dispatch the first taps onto SmartClickOCR's own screen.
        val refusal = service.startAutomation(profile, startPaused = true)
        if (refusal != null) {
            AlertDialog.Builder(this)
                .setTitle("无法启动")
                .setMessage(refusal)
                .setPositiveButton("知道了", null)
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("已就绪，等待开始")
                .setMessage(
                    "任务已准备好，但还没有执行。\n\n" +
                        "现在请切换到要操作的目标 App，然后点击屏幕上的悬浮球 →「开始」，" +
                        "任务才会真正开始执行。\n\n" +
                        "这样可以保证动作不会点在 SmartClickOCR 自己的界面上。"
                )
                .setPositiveButton("去目标 App") { _, _ -> moveTaskToBack(true) }
                .setNegativeButton("留在这里", null)
                .show()
        }
    }

    private fun ensureServiceStarted(): AutomationService? {
        return try {
            AutomationService.ensureStarted(this)
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "无法启动后台服务", Toast.LENGTH_LONG).show()
            null
        }
    }

    // -------------------------------------------------------------- permissions

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "请在列表中找到「SmartClickOCR 自动化」并开启", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开无障碍设置：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openOverlaySettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开悬浮窗设置：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        if (AutomationService.instance != null) {
            projectionLauncher.launch(manager.createScreenCaptureIntent())
            return
        }
        // No live service yet: start it and launch the dialog as soon as it reports ready.
        // ensureStarted() legitimately returns null here because onCreate is async, so the
        // return value must not be treated as failure.
        try {
            AutomationService.ensureStarted(this)
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "无法启动后台服务", Toast.LENGTH_LONG).show()
            return
        }
        pendingCaptureRequest = true
        Toast.makeText(this, "正在启动后台服务…", Toast.LENGTH_SHORT).show()
    }

    private var pendingCaptureRequest = false

    private fun openBatterySettings() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } catch (e: Exception) {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }
}
