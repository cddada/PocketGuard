package com.pocketguard.app

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.util.Log
import androidx.core.app.NotificationCompat

class PocketDetectService : Service() {

    companion object {
        const val CHANNEL_ID = "pocket_guard_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.pocketguard.STOP"
        private const val PREFS_NAME = "pocket_guard_preferences"
        private const val KEY_ENABLED = "detection_enabled"
        private const val TAG = "PocketDetectService"

        fun start(context: Context) {
            setEnabled(context, true)
            val intent = Intent(context, PocketDetectService::class.java)
            try {
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

        private fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, enabled)
                .apply()
        }
    }

    private lateinit var sensorMonitor: SensorMonitor
    private lateinit var stateMachine: PocketStateMachine
    private lateinit var touchOverlay: TouchOverlay
    private var latestSensors = PocketSensors()
    private var screenReceiverRegistered = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val probeConfirmation = Runnable {
        if (stateMachine.state == PocketState.PROBING) {
            // Proximity and light sensors often report only one on-change event.
            // Recheck the latest snapshot so protection does not depend on a
            // second callback after the confirmation interval.
            stateMachine.processSensors(latestSensors)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> handleScreenTurnedOn(treatAsActualWake = true)
                Intent.ACTION_SCREEN_OFF -> handleScreenTurnedOff()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        sensorMonitor = SensorMonitor(this)
        stateMachine = PocketStateMachine()
        touchOverlay = TouchOverlay(this)

        stateMachine.onShouldLockScreen = { lockScreen() }

        stateMachine.onStateChanged = { state ->
            Log.d(TAG, "State -> $state")
            when (state) {
                PocketState.POCKETED -> {
                    touchOverlay.enable()
                }
                PocketState.NORMAL -> {
                    touchOverlay.disable()
                }
                PocketState.LOCK_SCREEN -> {
                    touchOverlay.disable()
                }
                PocketState.PROBING -> scheduleProbeConfirmation()
            }

            if (state != PocketState.PROBING) cancelProbeConfirmation()
        }

        sensorMonitor.onSensorUpdate = { sensors ->
            latestSensors = sensors
            stateMachine.processSensors(sensors)
        }

        touchOverlay.onTouchDetected = {
            stateMachine.processSensors(latestSensors.copy(touchDetected = true))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (!screenReceiverRegistered) {
            registerReceiver(screenReceiver, filter)
            screenReceiverRegistered = true
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isInteractive) {
            handleScreenTurnedOn(treatAsActualWake = false)
        } else {
            handleScreenTurnedOff()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        cancelProbeConfirmation()
        sensorMonitor.stop()
        touchOverlay.disable()
        if (screenReceiverRegistered) {
            unregisterReceiver(screenReceiver)
            screenReceiverRegistered = false
        }
        super.onDestroy()
    }

    private fun handleScreenTurnedOn(treatAsActualWake: Boolean) {
        cancelProbeConfirmation()
        latestSensors = PocketSensors()
        stateMachine.onScreenTurnedOn(treatAsActualWake)
        sensorMonitor.start()
    }

    private fun handleScreenTurnedOff() {
        cancelProbeConfirmation()
        stateMachine.onScreenTurnedOff()
        sensorMonitor.stop()
        latestSensors = PocketSensors()
    }

    private fun scheduleProbeConfirmation() {
        mainHandler.removeCallbacks(probeConfirmation)
        mainHandler.postDelayed(probeConfirmation, stateMachine.probeConfirmationDelayMs)
    }

    private fun cancelProbeConfirmation() {
        mainHandler.removeCallbacks(probeConfirmation)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun lockScreen(): Boolean {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, PocketDeviceAdminReceiver::class.java)
        if (!dpm.isAdminActive(admin)) {
            Log.w(TAG, "Cannot lock screen because device admin is not active")
            return false
        }
        return try {
            dpm.lockNow()
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Device policy manager rejected lockNow", e)
            false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "口袋防误触服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "口袋防误触检测正在运行"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, PocketDetectService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PocketGuard 运行中")
            .setContentText("正在监听口袋状态...")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "停止", stopPending)
            .build()
    }
}
