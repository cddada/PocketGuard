package com.pocketguard.app

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    companion object {
        private const val REQUEST_NOTIFICATION = 1002
        private const val REQUEST_DEVICE_ADMIN = 1003
        private const val REQUEST_OVERLAY = 1001
    }

    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { updateUI() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        makeStatusBarTransparent(findViewById(R.id.page_root))
        btnToggle = findViewById(R.id.btn_toggle)
        tvStatus = findViewById(R.id.tv_status)
        findViewById<Button>(R.id.btn_guide).setOnClickListener {
            startActivity(Intent(this, GuideActivity::class.java))
        }
        btnToggle.setOnClickListener {
            if (PocketDetectService.isRunning(this)) stopDetectService() else startDetectService()
        }
        updateUI()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, statusReceiver,
            IntentFilter(PocketDetectService.ACTION_STATUS_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        unregisterReceiver(statusReceiver)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun startDetectService() {
        if (!PocketDetectService.isSupported(this)) {
            Toast.makeText(this, "此设备没有距离传感器", Toast.LENGTH_LONG).show()
            return
        }
        if (!PocketDetectService.isAdminActive(this)) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this@MainActivity, PocketDeviceAdminReceiver::class.java))
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "用于遮挡距离传感器时立即锁屏")
            }
            startActivityForResult(intent, REQUEST_DEVICE_ADMIN)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "需要悬浮窗权限，在持续遮挡后检测触屏", Toast.LENGTH_LONG).show()
            startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")), REQUEST_OVERLAY)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION)
            return
        }
        try {
            PocketDetectService.start(this)
            updateUI()
            Toast.makeText(this, "距离感应锁屏已启动", Toast.LENGTH_SHORT).show()
        } catch (e: RuntimeException) {
            updateUI()
            Toast.makeText(this, "检测服务启动失败：${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopDetectService() {
        PocketDetectService.stop(this)
        updateUI()
        Toast.makeText(this, "距离感应锁屏已停止", Toast.LENGTH_SHORT).show()
    }

    private fun updateUI() {
        val running = PocketDetectService.isRunning(this)
        val supported = PocketDetectService.isSupported(this)
        btnToggle.text = if (running) "停止检测" else "启动检测"
        btnToggle.isEnabled = running || supported
        tvStatus.text = when {
            running -> "状态：运行中"
            !supported -> "状态：此设备没有距离传感器"
            else -> "状态：已停止"
        }
        tvStatus.setTextColor(ContextCompat.getColor(this,
            if (running) android.R.color.holo_green_dark else android.R.color.holo_red_dark))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATION && grantResults.isNotEmpty()) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) startDetectService()
            else Toast.makeText(this, "需要通知权限才能显示检测服务状态", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_DEVICE_ADMIN) {
            if (PocketDetectService.isAdminActive(this)) startDetectService()
            else Toast.makeText(this, "需要启用设备管理员才能自动锁屏", Toast.LENGTH_LONG).show()
        } else if (requestCode == REQUEST_OVERLAY) {
            if (Settings.canDrawOverlays(this)) startDetectService()
            else Toast.makeText(this, "需要悬浮窗权限才能检测遮挡后的触屏", Toast.LENGTH_LONG).show()
        }
    }
}
