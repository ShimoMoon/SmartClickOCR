package io.github.shimo.smartclickocr.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.shimo.smartclickocr.R
import io.github.shimo.smartclickocr.data.AutomationProfile
import io.github.shimo.smartclickocr.data.AutomationRepository
import io.github.shimo.smartclickocr.engine.ActionEngine
import io.github.shimo.smartclickocr.engine.ActionExecutor
import io.github.shimo.smartclickocr.engine.DeviceActionExecutor
import io.github.shimo.smartclickocr.model.AutomationAction
import io.github.shimo.smartclickocr.model.EngineState
import io.github.shimo.smartclickocr.model.EngineStatus
import io.github.shimo.smartclickocr.ocr.OcrManager
import io.github.shimo.smartclickocr.ocr.ScreenCaptureManager
import io.github.shimo.smartclickocr.ui.MainActivity
import io.github.shimo.smartclickocr.util.RunLog
import io.github.shimo.smartclickocr.util.ScreenMetricsProvider
import io.github.shimo.smartclickocr.overlay.OverlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Long-lived foreground service that owns the automation session.
 *
 * Lifecycle is deliberately independent of any Activity: the floating control stays
 * available after the user switches to another app (spec §5.2). All UI components
 * observe [state] so they can never drift out of sync.
 */
class AutomationService : Service() {

    companion object {
        const val ACTION_START = "io.github.shimo.smartclickocr.START"
        const val ACTION_PAUSE = "io.github.shimo.smartclickocr.PAUSE"
        const val ACTION_RESUME = "io.github.shimo.smartclickocr.RESUME"
        const val ACTION_STOP = "io.github.shimo.smartclickocr.STOP"
        const val ACTION_SHUTDOWN = "io.github.shimo.smartclickocr.SHUTDOWN"
        const val EXTRA_PROFILE_ID = "profile_id"

        private const val CHANNEL_ID = "automation_channel"
        private const val NOTIFICATION_ID = 1001

        private val _state = MutableStateFlow(EngineStatus())
        val state: StateFlow<EngineStatus> = _state.asStateFlow()

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        /** True once a MediaProjection consent has been applied in this service. */
        internal val _captureReady = MutableStateFlow(false)

        /**
         * True while a live service instance exists. The UI observes this to know when
         * it may safely ask for the MediaProjection grant (before the service exists,
         * `startForeground` with a mediaProjection type would be illegal).
         */
        internal val _serviceReady = MutableStateFlow(false)
        val serviceReady: StateFlow<Boolean> = _serviceReady.asStateFlow()

        @Volatile
        var instance: AutomationService? = null
            private set

        /**
         * Asks the system to start the service if one is not already running.
         *
         * Returns the live instance when available; otherwise null, because [onCreate]
         * runs asynchronously and has not published [instance] yet. Null is therefore
         * NOT a failure — it just means "started, not ready". Callers should start
         * capture from a later UI callback (`hasCaptureSession()` becomes true then).
         */
        fun ensureStarted(context: Context): AutomationService? {
            instance?.let { return it }
            val intent = Intent(context, AutomationService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e("AutomationService", "ensureStarted failed", e)
                throw ServiceStartException("无法启动前台服务：${e.message}", e)
            }
            return instance
        }

        class ServiceStartException(message: String, cause: Throwable? = null) : Exception(message, cause)
    }

    /** Calibration callbacks forwarded to [overlay]; set by the editor screen. */
    var onPointPicked: ((Float, Float) -> Unit)?
        get() = overlay.onPointPicked
        set(value) { overlay.onPointPicked = value }

    var onRegionPicked: ((Int, Int, Int, Int) -> Unit)?
        get() = overlay.onRegionPicked
        set(value) { overlay.onRegionPicked = value }

    var onCalibrationCancelled: (() -> Unit)?
        get() = overlay.onCalibrationCancelled
        set(value) { overlay.onCalibrationCancelled = value }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: AutomationRepository
    private lateinit var capture: ScreenCaptureManager
    private lateinit var ocr: OcrManager
    private lateinit var overlay: OverlayController
    private var engine: ActionEngine? = null
    private var statusJob: Job? = null

    /** True once the notification declares the mediaProjection FGS type. */
    private var _projectionTypeActive = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        ScreenMetricsProvider.attach(this)
        repository = AutomationRepository(this)
        capture = ScreenCaptureManager(this)
        ocr = OcrManager()
        // Overlay first: the notification requires no overlay work, but any overlay
        // callback must find a fully initialised service.
        overlay = OverlayController(this, serviceScope).apply {
            onPanelStart = { resumeOrArm() }
            onPanelPause = { pauseAutomation() }
            onPanelResume = { resumeAutomation() }
            onPanelStop = { stopAutomation() }
            onPanelClose = {
                // Collapsing the control must never restart the task.
                overlay.removeAll()
            }
        }
        createNotificationChannel()
        // When the service is re-created for an already-authorised session, the
        // notification must immediately re-declare the projection type.
        val reattachProjection = _captureReady.value
        startForegroundInternal(getString(R.string.notif_idle), includeProjection = reattachProjection)
        _projectionTypeActive = reattachProjection
        _serviceReady.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)
                startAutomation(profileId)
            }
            ACTION_PAUSE -> engine?.pause()
            ACTION_RESUME -> engine?.resume()
            ACTION_STOP -> stopAutomation()
            ACTION_SHUTDOWN -> {
                stopAutomation()
                overlay.removeAll()
                stopSelf()
            }
            else -> Unit
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------ automation

    private fun startAutomation(profileId: String?) {
        val snapshot = repository.load()
        val profile = snapshot.profiles.firstOrNull { it.id == profileId }
            ?: snapshot.profiles.firstOrNull { it.id == snapshot.selectedProfileId }
            ?: snapshot.profiles.firstOrNull()
        if (profile == null) {
            RunLog.add("启动失败：没有可用的方案")
            return
        }
        startAutomation(profile)
    }

    /**
     * Prepares the loop for [profile].
     *
     * @param startPaused when true (the normal case when starting from the app UI) the
     *   task is armed but does not run: the user still has to switch to the target app
     *   and press 开始/继续 on the floating panel. Without this, starting from the app
     *   would immediately tap on SmartClickOCR's own screen.
     * @return a refusal reason, or null when the task was armed/started.
     */
    fun startAutomation(profile: AutomationProfile, startPaused: Boolean = true): String? {
        if (engine?.isRunning == true) return "任务已在运行，不能重复启动"
        // A previously armed (paused) task must be cleared first, otherwise the new run
        // would be rejected as "already running".
        if (_state.value.state.isActive) {
            engine?.stop()
        }

        // Accept the system setting as well: the live instance is bound asynchronously
        // and may lag a moment behind, which should not block starting.
        if (!isAccessibilityServiceEnabled()) {
            val msg = "无障碍服务未启用，无法开始自动化"
            RunLog.add(msg)
            return msg
        }

        val executor: ActionExecutor = DeviceActionExecutor(
            capture = capture,
            ocr = ocr,
            onTapFeedback = { x, y -> overlay.showTapFeedback(x, y) },
            onOcrDebug = { RunLog.add(it) }
        )

        engine = ActionEngine(
            scope = serviceScope,
            executor = executor,
            logger = { RunLog.add(it) }
        ).also { eng ->
            statusJob?.cancel()
            statusJob = serviceScope.launch {
                eng.status.collect { status ->
                    _state.value = status
                    _running.value = status.state.isActive
                    updateNotification(status)
                    overlay.onStatusChanged(status)
                    if (!status.state.isActive) {
                        overlay.showFinishedState(status)
                    }
                }
            }
        }

        val refusal = engine!!.start(profile.actions, profile.name, startPaused = startPaused)
        if (refusal != null) {
            RunLog.add("启动失败：$refusal")
            statusJob?.cancel()
            return refusal
        }

        overlay.showControl()
        _running.value = true
        return null
    }

    /** True when our accessibility service is enabled in system settings or connected. */
    private fun isAccessibilityServiceEnabled(): Boolean {
        if (AutomationAccessibilityService.isConnected()) return true
        val enabled = runCatching {
            Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull() ?: return false
        val me = "$packageName/${AutomationAccessibilityService::class.java.name}"
        val meShort = "$packageName/.service.AutomationAccessibilityService"
        return enabled.split(':').any { it.equals(me, true) || it.equals(meShort, true) }
    }

    fun pauseAutomation() = engine?.pause()
    fun resumeAutomation() = engine?.resume()

    /**
     * The floating panel's 开始 button.
     *
     * - an armed/PAUSED task resumes from the same action (keeping its leftover delay);
     * - otherwise the last profile is armed again from the beginning and immediately run.
     */
    fun resumeOrArm() {
        val current = _state.value.state
        if (current == EngineState.PAUSED) {
            engine?.resume()
            return
        }
        if (current == EngineState.RUNNING) return

        val snapshot = repository.load()
        val profile = snapshot.profiles.firstOrNull { it.id == snapshot.selectedProfileId }
            ?: snapshot.profiles.firstOrNull()
        if (profile == null) {
            RunLog.add("无法开始：没有可用的方案")
            return
        }
        // Started from the panel, so run immediately (startPaused = false).
        startAutomation(profile, startPaused = false)
    }

    fun stopAutomation() {
        engine?.stop()
        _running.value = false
    }

    fun currentStatus(): EngineStatus = _state.value

    // --------------------------------------------------------------- capture

    /** True when a MediaProjection session is already alive. */
    fun hasCaptureSession(): Boolean = capture.isActive

    fun startCapture(resultCode: Int, data: Intent): String? {
        if (capture.isActive) return null
        // The projection must be created from a real display size so screen pixels and
        // screenshot pixels are 1:1 (insets only affect where overlays are placed).
        val display = ScreenMetricsProvider.realDisplaySize()

        // The service must ALREADY be a mediaProjection foreground service before
        // getMediaProjection()/createVirtualDisplay() is called, otherwise Android
        // throws "Media projections require a foreground service of type
        // FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION". Declaring the type is allowed as
        // soon as the user has consented to the projection, which is already true by
        // the time this method runs (it receives the consent result).
        val promoted = promoteToMediaProjectionService()
        if (!promoted) {
            val msg = "无法将服务切换到屏幕捕获前台类型，请重启应用后重试"
            RunLog.add(msg)
            return msg
        }

        return try {
            capture.start(
                resultCode = resultCode,
                data = data,
                width = display.widthPx,
                height = display.heightPx,
                densityDpi = ScreenMetricsProvider.densityDpi(),
                sessionRotation = display.rotation
            )
            RunLog.add("屏幕捕获已授权")
            _captureReady.value = true
            null
        } catch (e: Exception) {
            RunLog.add("屏幕捕获失败：${e.message}")
            e.message ?: "屏幕捕获失败"
        }
    }

    /**
     * Re-declares the foreground notification with the mediaProjection type.
     *
     * Must be called BEFORE creating the projection. Returns false when the system
     * rejects the type (e.g. SecurityException on an OEM build), so the caller can
     * report a clear error instead of letting MediaProjection fail obscurely.
     */
    private fun promoteToMediaProjectionService(): Boolean {
        if (_projectionTypeActive) return true
        return try {
            startForegroundInternal(getString(R.string.notif_idle), includeProjection = true)
            _projectionTypeActive = true
            true
        } catch (e: Exception) {
            Log.e("AutomationService", "Failed to promote to mediaProjection FGS", e)
            RunLog.add("切换到屏幕捕获服务失败：${e.message}")
            false
        }
    }

    /** One-off capture+recognise used by the editor's "test OCR" button. */
    suspend fun testOcr(actions: AutomationAction): Pair<String?, String?> {
        // Returns (recognisedText, errorMessage)
        if (!capture.isActive) return Pair(null, "屏幕捕获会话未启动，请先授权截图")
        var frame: android.graphics.Bitmap? = null
        return try {
            // captureBitmap blocks on the frame callback: never run it on the main thread.
            frame = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                capture.captureBitmap()
            }
            val meta = ScreenMetricsProvider.realDisplaySize()
            RunLog.add(
                "OCR 诊断：截图 ${frame.width}×${frame.height}，屏幕 ${meta.widthPx}×${meta.heightPx}，" +
                    "系统栏 上${ScreenMetricsProvider.systemBarInsets().top}/下${ScreenMetricsProvider.systemBarInsets().bottom}，" +
                    "区域 (${actions.regionLeft},${actions.regionTop})-(${actions.regionRight},${actions.regionBottom})"
            )
            val recognition = ocr.recognizeRegion(
                fullFrame = frame,
                screenMeta = meta,
                left = actions.regionLeft,
                top = actions.regionTop,
                right = actions.regionRight,
                bottom = actions.regionBottom
            )
            // Persist the exact pixels that were sent to OCR, so a bad region is visible.
            val saved = saveOcrDebugCrop(frame, recognition.regionInCapture)
            lastOcrDiagnostics = buildString {
                append("截图 ${frame.width}×${frame.height}")
                append(" · 屏幕 ${meta.widthPx}×${meta.heightPx}")
                append(" · 系统栏上${ScreenMetricsProvider.systemBarInsets().top}")
                append(" · 实际裁剪 ${recognition.regionInCapture.toList()}")
                if (saved != null) append("\n裁剪图目录：$saved")
            }
            RunLog.add(
                "OCR 诊断：截图 ${frame.width}×${frame.height}，裁剪 ${recognition.regionInCapture.toList()}，" +
                    "识别到 ${recognition.blocks.size} 个文本块"
            )
            Pair(recognition.text, null)
        } catch (e: Exception) {
            Pair(null, e.message ?: "OCR 失败")
        } finally {
            runCatching { frame?.recycle() }
        }
    }

    /**
     * Writes the cropped OCR input to the app's cache dir and returns its path.
     * Only ever called from the explicit "test OCR" action, never during automation.
     */
    private fun saveOcrDebugCrop(
        frame: android.graphics.Bitmap,
        region: IntArray
    ): String? = runCatching {
        val w = region[2] - region[0]
        val h = region[3] - region[1]
        if (w <= 0 || h <= 0) return@runCatching null
        val crop = android.graphics.Bitmap.createBitmap(frame, region[0], region[1], w, h)
        val dir = java.io.File(cacheDir, "ocr-debug").apply { mkdirs() }
        // Keep only the newest few so the cache cannot grow without bound.
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(6)?.forEach { it.delete() }
        val stamp = System.currentTimeMillis()

        // 1) The exact crop that was handed to OCR.
        java.io.FileOutputStream(java.io.File(dir, "crop-$stamp.png")).use { out ->
            crop.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }
        // 2) The whole captured frame with the region outlined, so the user can tell a
        //    mis-aimed region from a blank capture. JPEG keeps this small.
        runCatching {
            val marked = frame.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
            val canvas = android.graphics.Canvas(marked)
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.RED
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 8f
            }
            canvas.drawRect(
                region[0].toFloat(), region[1].toFloat(),
                region[2].toFloat(), region[3].toFloat(), paint
            )
            java.io.FileOutputStream(java.io.File(dir, "frame-$stamp.jpg")).use { out ->
                marked.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out)
            }
            marked.recycle()
        }
        if (crop !== frame) crop.recycle()
        "$dir"
    }.getOrNull()

    /** Shows/hides overlays used by the calibration screens. */
    fun showOverlay(mode: OverlayController.Mode) {
        overlay.showMode(mode)
    }

    /** Geometry of the most recent OCR test, for display in the editor. */
    @Volatile
    private var lastOcrDiagnostics: String = ""

    fun lastOcrDiagnostics(): String = lastOcrDiagnostics

    fun hideCalibrationOverlay() = overlay.hideCalibration()

    /** Shows the short ephemeral ring at a screen point (visual feedback only). */
    fun showTapFeedback(x: Float, y: Float) = overlay.showTapFeedback(x, y)

    // ---------------------------------------------------------- notification

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    /**
     * Starts (or re-declares) the foreground notification.
     *
     * Android 14 rejects a `mediaProjection` foreground service unless the
     * MediaProjection consent has already been granted, so the service starts as
     * `specialUse` only and [startCapture] upgrades it once the grant arrives.
     */
    private fun startForegroundInternal(text: String, includeProjection: Boolean = false) {
        val notification = buildNotification(null, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // NOTE: do NOT gate this on capture.isActive. The type must be declared
            // BEFORE the projection exists (see promoteToMediaProjectionService), and
            // isActive is necessarily false at that moment.
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (includeProjection) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            startForeground(NOTIFICATION_ID, notification, types)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(status: EngineStatus?, fallbackText: String? = null): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, AutomationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("SmartClickOCR")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (status == null) {
            builder.setContentText(fallbackText ?: getString(R.string.notif_idle))
        } else {
            val line = "${status.state.displayName} · ${status.progressText()}".trim().trimEnd('·', ' ')
            builder.setContentText(
                status.errorMessage?.let { "$line · $it" } ?: line.ifBlank { status.message }
            )
            if (status.state.isActive) {
                builder.addAction(0, getString(R.string.stop), stopIntent)
            }
        }
        return builder.build()
    }

    private fun updateNotification(status: EngineStatus) {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification(status))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        _serviceReady.value = false
        statusJob?.cancel()
        engine?.stop()
        runCatching { overlay.removeAll() }
        runCatching { capture.stop() }
        runCatching { ocr.close() }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep running so a background task is not killed when the UI is swiped away.
        super.onTaskRemoved(rootIntent)
    }
}
