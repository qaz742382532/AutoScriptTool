package com.example.autoscripttool

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "grab_settings"
        const val KEY_MIN_AMOUNT = "min_amount"
        const val KEY_MAX_DISTANCE = "max_distance"
        const val KEY_BLOCK_KEYWORDS = "block_keywords"

        const val DEFAULT_MIN_AMOUNT = 20f
        const val DEFAULT_MAX_DISTANCE = 5f
        const val DEFAULT_BLOCK_KEYWORDS = ""
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var tvStatus: TextView
    private lateinit var etMinAmount: EditText
    private lateinit var etMaxDistance: EditText
    private lateinit var etBlockKeywords: EditText
    private lateinit var btnSave: Button
    private lateinit var btnOpenScript: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        tvStatus = findViewById(R.id.tvStatus)
        etMinAmount = findViewById(R.id.etMinAmount)
        etMaxDistance = findViewById(R.id.etMaxDistance)
        etBlockKeywords = findViewById(R.id.etBlockKeywords)
        btnSave = findViewById(R.id.btnSave)
        btnOpenScript = findViewById(R.id.btnOpenScript)

        loadPrefs()
        updateServiceStatus()

        btnSave.setOnClickListener { savePrefs() }
        btnOpenScript.setOnClickListener {
            if (isAccessibilityServiceEnabled()) {
                Toast.makeText(this, "服务已开启，正在监控中 ✅", Toast.LENGTH_SHORT).show()
            } else {
                openAccessibilitySettings()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }

    private fun loadPrefs() {
        val amount = prefs.getFloat(KEY_MIN_AMOUNT, DEFAULT_MIN_AMOUNT)
        val distance = prefs.getFloat(KEY_MAX_DISTANCE, DEFAULT_MAX_DISTANCE)
        val keywords = prefs.getString(KEY_BLOCK_KEYWORDS, DEFAULT_BLOCK_KEYWORDS) ?: ""
        etMinAmount.setText(formatFloat(amount))
        etMaxDistance.setText(formatFloat(distance))
        etBlockKeywords.setText(keywords)
    }

    private fun savePrefs() {
        val amountStr = etMinAmount.text.toString().trim()
        val distStr = etMaxDistance.text.toString().trim()

        val amount = amountStr.toFloatOrNull()
        val dist = distStr.toFloatOrNull()

        if (amount == null || amount <= 0) {
            Toast.makeText(this, "请输入有效的最低金额", Toast.LENGTH_SHORT).show()
            return
        }
        if (dist == null || dist <= 0) {
            Toast.makeText(this, "请输入有效的接驾距离", Toast.LENGTH_SHORT).show()
            return
        }

        prefs.edit()
            .putFloat(KEY_MIN_AMOUNT, amount)
            .putFloat(KEY_MAX_DISTANCE, dist)
            .putString(KEY_BLOCK_KEYWORDS, etBlockKeywords.text.toString().trim())
            .apply()

        Toast.makeText(this, "设置已保存 ✅", Toast.LENGTH_SHORT).show()
    }

    private fun formatFloat(value: Float): String {
        return if (value == value.toLong().toFloat()) {
            value.toLong().toString()
        } else {
            value.toString()
        }
    }

    // ── 状态 ───────────────────────────────────────────────────

    private fun isAccessibilityServiceEnabled(): Boolean {
        // 两种格式都测：完整类名 + 简写类名（部分 ROM 用 .AutoClickService）
        val full = "${packageName}/${AutoClickService::class.java.name}"
        val short = "${packageName}/.AutoClickService"
        val raw = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        // 直接用 contains，不管 ROM 用什么分隔符/顺序
        return raw.contains(full, ignoreCase = true) || raw.contains(short, ignoreCase = true)
    }

    private fun updateServiceStatus() {
        if (isAccessibilityServiceEnabled()) {
            tvStatus.text = getString(R.string.status_service_on)
            tvStatus.setTextColor(resources.getColor(android.R.color.holo_green_dark, theme))
        } else {
            tvStatus.text = getString(R.string.status_service_off)
            tvStatus.setTextColor(resources.getColor(android.R.color.darker_gray, theme))
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}