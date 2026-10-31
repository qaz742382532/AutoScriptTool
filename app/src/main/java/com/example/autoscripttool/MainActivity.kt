package com.example.autoscripttool

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnOpenScript: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        btnOpenScript = findViewById(R.id.btnOpenScript)

        updateServiceStatus()

        btnOpenScript.setOnClickListener {
            openAccessibilitySettings()
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }

    /** 检查无障碍服务是否已开启 */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "${packageName}/${AutoClickService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(":").any { it.equals(service, ignoreCase = true) }
    }

    /** 更新界面的服务状态 */
    private fun updateServiceStatus() {
        if (isAccessibilityServiceEnabled()) {
            tvStatus.text = getString(R.string.status_service_on)
            tvStatus.setTextColor(resources.getColor(android.R.color.holo_green_dark, theme))
        } else {
            tvStatus.text = getString(R.string.status_service_off)
            tvStatus.setTextColor(resources.getColor(android.R.color.darker_gray, theme))
        }
    }

    /** 跳转到系统无障碍设置页面 */
    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }
}