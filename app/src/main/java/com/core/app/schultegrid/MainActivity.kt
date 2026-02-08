package com.core.app.schultegrid

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val OVERLAY_PERMISSION_REQUEST_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnStartService = findViewById<Button>(R.id.btn_start_service)
        val btnOpenAccessibility = findViewById<Button>(R.id.btn_open_accessibility)
        val btnRequestOverlay = findViewById<Button>(R.id.btn_request_overlay)
        val btnStartWc = findViewById<Button>(R.id.btn_start_wc)
        val btnStartFloatingWindow = findViewById<Button>(R.id.btn_start_floating_window)

        btnOpenAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        btnRequestOverlay.setOnClickListener {
            requestOverlayPermission()
        }

        btnStartWc.setOnClickListener {
            val startWc = SchulteGridAccessibilityService.startWc
            SchulteGridAccessibilityService.startWc = !startWc
            updateStartWcButton()
        }

        btnStartFloatingWindow.setOnClickListener {
            if (!canDrawOverlays()) {
                Toast.makeText(this, "请先授权悬浮窗权限", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (isAccessibilityServiceEnabled()) {
                startFloatingWindow()
                Toast.makeText(this, "悬浮窗已启动", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "请先启用无障碍服务", Toast.LENGTH_SHORT).show()
            }
        }

        btnStartService.setOnClickListener {
            if (isAccessibilityServiceEnabled()) {
                if (canDrawOverlays()) {
                    startFloatingWindow()
                    Toast.makeText(this, "无障碍服务已启用，悬浮窗已启动", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "请先授权悬浮窗权限", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "请先启用无障碍服务", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateStartWcButton() {
        val btnStartWc = findViewById<Button>(R.id.btn_start_wc)
        btnStartWc.text = if (SchulteGridAccessibilityService.startWc) "关闭误差" else "启动误差"
    }

    private fun requestOverlayPermission() {
        if (canDrawOverlays()) {
            Toast.makeText(this, "悬浮窗权限已授予", Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
        }
    }

    private fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "${packageName}/${SchulteGridAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServices.contains(serviceName)
    }

    private fun startFloatingWindow() {
        val intent = Intent(this, FloatingWindowService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        finish() // 关闭主界面，只保留悬浮窗
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
            if (canDrawOverlays()) {
                Toast.makeText(this, "悬浮窗权限已授予", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "悬浮窗权限被拒绝", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateStartWcButton()
    }
}
