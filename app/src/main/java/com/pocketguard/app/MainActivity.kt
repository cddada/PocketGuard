package com.pocketguard.app

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
        private const val REQUEST_OVERLAY = 1001
        private const val REQUEST_NOTIFICATION = 1002
        private const val REQUEST_DEVICE_ADMIN = 1003
    }

    private lateinit var btnToggle: Button
    private lateinit var btnDeviceAdmin: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvSensorInfo: TextView
    private var serviceRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnToggle = findViewById(R.id.btn_toggle)
        btnDeviceAdmin = findViewById(R.id.btn_device_admin)
        tvStatus = findViewById(R.id.tv_status)
        tvSensorInfo = findViewById(R.id.tv_sensor_info)

        btnToggle.setOnClickListener {
            if (serviceRunning) {
                stopDetectService()
            } else {
                startDetectService()
            }
        }

        btnDeviceAdmin.setOnClickListener {
            requestDeviceAdmin()
        }

        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun startDetectService() {
        // Check overlay permission
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, REQUEST_OVERLAY)
            return
        }

        // Check notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION
                )
                return
            }
        }

        try {
            PocketDetectService.start(this)
            serviceRunning = true
            updateUI()
            Toast.makeText(this, "口袋防误触已启动", Toast.LENGTH_SHORT).show()
        } catch (e: RuntimeException) {
            serviceRunning = false
            updateUI()
            Toast.makeText(this, "检测服务启动失败：${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopDetectService() {
        PocketDetectService.stop(this)
        serviceRunning = false
        updateUI()
        Toast.makeText(this, "口袋防误触已停止", Toast.LENGTH_SHORT).show()
    }

    private fun requestDeviceAdmin() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val component = ComponentName(this, PocketDeviceAdminReceiver::class.java)

        if (dpm.isAdminActive(component)) {
            Toast.makeText(this, "设备管理员已激活", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "需要设备管理员权限以实现自动锁屏功能")
        }
        startActivityForResult(intent, REQUEST_DEVICE_ADMIN)
    }

    private fun updateUI() {
        serviceRunning = PocketDetectService.isEnabled(this)

        if (serviceRunning) {
            btnToggle.text = "停止检测"
            tvStatus.text = "状态：运行中"
            tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        } else {
            btnToggle.text = "启动检测"
            tvStatus.text = "状态：已停止"
            tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        }

        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val component = ComponentName(this, PocketDeviceAdminReceiver::class.java)
        btnDeviceAdmin.text = if (dpm.isAdminActive(component)) {
            "设备管理员 ✓"
        } else {
            "启用设备管理员（推荐）"
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATION && grantResults.isNotEmpty()) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startDetectService()
            } else {
                Toast.makeText(this, "需要通知权限才能显示检测服务状态", Toast.LENGTH_LONG).show()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_OVERLAY -> {
                if (Settings.canDrawOverlays(this)) {
                    startDetectService()
                } else {
                    Toast.makeText(this, "需要悬浮窗权限才能运行", Toast.LENGTH_LONG).show()
                }
            }
            REQUEST_DEVICE_ADMIN -> updateUI()
        }
    }
}
