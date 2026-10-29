package com.pocketguard.app

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class GuideActivity : AppCompatActivity() {
    private lateinit var detection: TextView
    private lateinit var permissions: TextView
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateUI()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guide)
        makeStatusBarTransparent(findViewById(R.id.page_root))
        detection = findViewById(R.id.tv_detection)
        permissions = findViewById(R.id.tv_permissions)
        val delayGroup = findViewById<RadioGroup>(R.id.delay_group)
        delayGroup.check(when (PocketDetectService.delaySeconds(this)) {
            2 -> R.id.delay_2
            5 -> R.id.delay_5
            else -> R.id.delay_3
        })
        delayGroup.setOnCheckedChangeListener { _, checkedId ->
            val seconds = when (checkedId) { R.id.delay_2 -> 2; R.id.delay_5 -> 5; else -> 3 }
            getSharedPreferences(PocketDetectService.PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putInt(PocketDetectService.KEY_DELAY_SECONDS, seconds).apply()
        }
        findViewById<Button>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_app_settings).setOnClickListener {
            openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.btn_battery_settings).setOnClickListener {
            openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, statusReceiver,
            IntentFilter(PocketDetectService.ACTION_STATUS_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    override fun onStop() {
        unregisterReceiver(statusReceiver)
        super.onStop()
    }

    private fun updateUI() {
        val running = PocketDetectService.isRunning(this)
        detection.text = if (running) PocketDetectService.detectionStatus else "已停止"
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        fun flag(value: Boolean) = if (value) "√" else "×"
        permissions.text = "距离传感器　${flag(PocketDetectService.isSupported(this))}\n" +
            "设备管理员　${flag(PocketDetectService.isAdminActive(this))}\n" +
            "悬浮窗　${flag(Settings.canDrawOverlays(this))}\n" +
            "通知　${flag(NotificationManagerCompat.from(this).areNotificationsEnabled())}\n" +
            "电池优化不受限制　${flag(power.isIgnoringBatteryOptimizations(packageName))}"
    }

    private fun openSettings(intent: Intent) {
        try { startActivity(intent) }
        catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "无法打开设置，请在系统设置中查找 PocketGuard", Toast.LENGTH_LONG).show()
        }
    }
}
