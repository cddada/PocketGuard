package com.pocketguard.app

import android.app.Notification
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Display
import androidx.core.app.NotificationCompat

class PocketDetectService : Service(), SensorEventListener {
    companion object {
        const val CHANNEL_ID = "pocket_guard_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.pocketguard.STOP"
        const val PREFS_NAME = "pocket_guard_preferences"
        const val KEY_DELAY_SECONDS = "cover_delay_seconds"
        const val ACTION_STATUS_CHANGED = "com.pocketguard.app.STATUS_CHANGED"
        private const val KEY_ENABLED = "detection_enabled"
        private const val KEY_WAITING_FOR_CLEAR = "waiting_for_clear"
        private const val TAG = "PocketDetectService"
        var detectionStatus = "已停止"
            private set

        fun delaySeconds(context: Context): Int =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_DELAY_SECONDS, 3).let { if (it in listOf(2, 3, 5)) it else 3 }

        fun isSupported(context: Context): Boolean {
            val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            return sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null
        }

        fun isAdminActive(context: Context): Boolean =
            (context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager)
                .isAdminActive(ComponentName(context, PocketDeviceAdminReceiver::class.java))

        fun start(context: Context) {
            check(isSupported(context)) { "此设备没有距离传感器" }
            check(isAdminActive(context)) { "请先启用设备管理员" }
            check(Settings.canDrawOverlays(context)) { "请先启用悬浮窗权限" }
            setEnabled(context, true)
            try {
                val intent = Intent(context, PocketDetectService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: RuntimeException) {
                setEnabled(context, false)
                throw e
            }
        }

        fun stop(context: Context) {
            setEnabled(context, false)
            context.stopService(Intent(context, PocketDetectService::class.java))
        }

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)

        @Suppress("DEPRECATION")
        fun isRunning(context: Context): Boolean =
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                .getRunningServices(Int.MAX_VALUE)
                .any { it.service.className == PocketDetectService::class.java.name && it.started }

        private fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).apply()
        }
    }

    private lateinit var sensorManager: SensorManager
    private var proximitySensor: Sensor? = null
    private var registered = false
    private var wasNear: Boolean? = null
    private lateinit var rearmGate: LockRearmGate
    private lateinit var preferences: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var displayManager: DisplayManager
    private lateinit var touchOverlay: TouchOverlay
    private var lastLandscape = false
    private val armAfterCover = Runnable { armTouchDetection() }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                cancelTouchDetection()
                updateDetectionStatus()
            } else if (intent.action == Intent.ACTION_SCREEN_ON) {
                if (wasNear == true) scheduleLock() else updateDetectionStatus()
            }
        }
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val landscape = isLandscape()
            if (landscape == lastLandscape) return
            lastLandscape = landscape
            cancelTouchDetection()
            Log.i(TAG, "Display orientation: ${if (landscape) "landscape" else "portrait"}")
            if (wasNear == true && !landscape) scheduleLock() else updateDetectionStatus()
        }
    }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_DELAY_SECONDS) {
            cancelTouchDetection()
            if (wasNear == true) scheduleLock() else updateDetectionStatus()
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        preferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        rearmGate = LockRearmGate(preferences.getBoolean(KEY_WAITING_FOR_CLEAR, false))
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        touchOverlay = TouchOverlay(this).apply { onTouchDetected = { lockIfStillCovered() } }
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
        lastLandscape = isLandscape()
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY, true)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !isEnabled(this)) {
            setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        val sensor = proximitySensor
        if (sensor == null || !isAdminActive(this) || !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Proximity sensor, device admin, or overlay permission unavailable")
            setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            if (!registered) {
                registered = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                check(registered) { "Unable to register proximity sensor" }
                displayManager.registerDisplayListener(displayListener, handler)
                updateDetectionStatus()
                Log.i(TAG, "Proximity monitoring started: ${sensor.name}")
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "Unable to enable proximity screen-off", e)
            setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        cancelTouchDetection()
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        displayManager.unregisterDisplayListener(displayListener)
        unregisterReceiver(screenReceiver)
        if (registered) sensorManager.unregisterListener(this)
        registered = false
        publishStatus("已停止")
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_PROXIMITY || event.values.isEmpty()) return
        val near = event.values[0] < event.sensor.maximumRange
        if (near == wasNear) return
        wasNear = near
        Log.i(TAG, "Proximity changed: ${if (near) "near" else "far"}")
        cancelTouchDetection()
        if (!near && rearmGate.waitingForClear) {
            rearmGate.onSensorClear()
            preferences.edit().putBoolean(KEY_WAITING_FOR_CLEAR, false).apply()
            Log.i(TAG, "Sensor clear; lock rearmed")
        }
        if (near) scheduleLock() else updateDetectionStatus()
    }

    private fun scheduleLock() {
        if (rearmGate.waitingForClear) {
            updateDetectionStatus()
            return
        }
        if (isLandscape()) {
            Log.i(TAG, "Screen is landscape; automatic locking paused")
            updateDetectionStatus()
            return
        }
        if ((getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive) {
            publishStatus("已遮挡，计时 ${delaySeconds(this)} 秒")
            handler.postDelayed(armAfterCover, delaySeconds(this) * 1000L)
        } else {
            updateDetectionStatus()
        }
    }

    private fun cancelTouchDetection() {
        handler.removeCallbacks(armAfterCover)
        touchOverlay.disable()
    }

    private fun armTouchDetection() {
        if (wasNear != true || rearmGate.waitingForClear || isLandscape() || !(getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive) {
            updateDetectionStatus()
            return
        }
        if (!touchOverlay.enable()) {
            Log.e(TAG, "Unable to activate touch detection; stopping")
            setEnabled(this, false)
            stopSelf()
            return
        }
        publishStatus("已遮挡，等待触屏")
        Log.i(TAG, "Cover confirmed; waiting for touch")
    }

    @Suppress("DEPRECATION")
    private fun isLandscape(): Boolean {
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return false
        val size = Point()
        display.getRealSize(size)
        return size.x > size.y
    }

    private fun lockIfStillCovered() {
        if (!touchOverlay.isEnabled() || wasNear != true || rearmGate.waitingForClear || isLandscape() ||
            !(getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive) {
            cancelTouchDetection()
            return
        }
        cancelTouchDetection()
        if (!isAdminActive(this)) {
            Log.w(TAG, "Device admin revoked; stopping detection")
            setEnabled(this, false)
            stopSelf()
            return
        }
        try {
            rearmGate.onLockRequested()
            preferences.edit().putBoolean(KEY_WAITING_FOR_CLEAR, true).commit()
            (getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).lockNow()
            updateDetectionStatus()
            Log.i(TAG, "Device lock requested")
        } catch (e: SecurityException) {
            Log.e(TAG, "Device lock rejected", e)
            setEnabled(this, false)
            stopSelf()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun updateDetectionStatus() {
        val interactive = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        publishStatus(when {
            rearmGate.waitingForClear -> "已锁屏，等待传感器无遮挡后恢复"
            !interactive -> "屏幕已关闭，等待唤醒"
            isLandscape() -> "横屏，自动锁屏已暂停"
            wasNear == null -> "等待传感器数据"
            wasNear == false -> "未遮挡，正在检测"
            touchOverlay.isEnabled() -> "已遮挡，等待触屏"
            else -> "已遮挡，等待检测"
        })
    }

    private fun publishStatus(status: String) {
        if (detectionStatus == status) return
        detectionStatus = status
        sendBroadcast(Intent(ACTION_STATUS_CHANGED).setPackage(packageName))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "距离感应锁屏服务", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "距离感应锁屏正在运行"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, PocketDetectService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PocketGuard 运行中")
            .setContentText("遮挡 ${delaySeconds(this)} 秒后触屏锁屏，横屏时暂停")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "停止", stopPending)
            .build()
    }
}
