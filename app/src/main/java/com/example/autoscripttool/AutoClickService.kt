package com.example.autoscripttool

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 自动抢单无障碍服务
 *
 * 监听首汽约车司机端 (com.ichinait.gbdriver) 的订单窗口，
 * 根据起点/终点距离和用车时间类型判断是否抢单，自动模拟点击。
 */
@SuppressLint("NewApi")
class AutoClickService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoScriptTool"

        @Volatile
        var instance: AutoClickService? = null
            private set
    }

    // ── 订单状态 ───────────────────────────────────────────────

    private var topType = ""          // 用车时间类型（即时用车 / 预约等）
    private var startDistance = ""    // 起点距离
    private var endDistance = ""      // 终点距离
    private var startAddress = ""     // 起点地址
    private var endAddress = ""       // 终点地址

    // "抢"按钮的屏幕坐标
    private var tapCenterX = 0
    private var tapCenterY = 0

    // 状态机：防止同一订单重复处理
    private var hasProcessed = false

    // ── 生命周期 ───────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "服务已连接")

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_2_FINGER_PASSTHROUGH
            }
            notificationTimeout = 100
        }
        setServiceInfo(info)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val source = event.source ?: return

        // ── 查找"抢"按钮 ──
        val robNodes = source.findAccessibilityNodeInfosByViewId(
            "com.ichinait.gbdriver:id/tv_scramble_order"
        )
        if (robNodes.isEmpty()) return

        val robNode = robNodes.firstOrNull { it.text == "抢" } ?: return

        // 同一窗口只处理一次
        if (hasProcessed) return
        hasProcessed = true

        Log.d(TAG, "══════════ 新订单 ══════════")

        // ── 解析"抢"按钮坐标 ──
        val bounds = Rect()
        robNode.getBoundsInScreen(bounds)
        tapCenterX = bounds.centerX()
        tapCenterY = bounds.centerY()

        // ── 读取用车时间类型 ──
        source.findAccessibilityNodeInfosByViewId(
            "com.ichinait.gbdriver:id/tv_rob_top_type"
        ).firstOrNull()?.let {
            topType = it.text?.toString() ?: ""
            Log.d(TAG, "用车时间: $topType")
        }

        // ── 读取起点地址 ──
        source.findAccessibilityNodeInfosByViewId(
            "com.ichinait.gbdriver:id/tv_rob_start_address"
        ).firstOrNull()?.let {
            startAddress = it.text?.toString() ?: ""
            Log.d(TAG, "起点: $startAddress")
        }

        // ── 读取终点地址 ──
        source.findAccessibilityNodeInfosByViewId(
            "com.ichinait.gbdriver:id/tv_rob_end_address"
        ).firstOrNull()?.let {
            endAddress = it.text?.toString() ?: ""
            Log.d(TAG, "终点: $endAddress")
        }

        // ── 读取起点距离 ──
        source.findAccessibilityNodeInfosByViewId(
            "com.ichinait.gbdriver:id/tv_rob_start_distance"
        ).firstOrNull()?.let { node ->
            val text = node.text?.toString() ?: ""
            if (text != "···") {
                startDistance = text
                Log.d(TAG, "起点距离: $startDistance")
            }
        }

        // ── 读取终点距离 ──
        source.findAccessibilityNodeInfosByViewId(
            "com.ichinait.gbdriver:id/tv_rob_end_distance"
        ).firstOrNull()?.let { node ->
            val text = node.text?.toString() ?: ""
            if (text != "···") {
                endDistance = text
                Log.d(TAG, "终点距离: $endDistance")
            }
        }

        // ── 抢单判断 ──
        decideGrab()
    }

    /**
     * 根据用车时间和距离条件决定是否抢单
     */
    private fun decideGrab() {
        val startM = kmToM(startDistance)
        val endM = kmToM(endDistance)

        if (topType == "即时用车") {
            when {
                // 起点近 (≤2km) 且 终点远 (≥5km)
                startM in 0..2000 && endM >= 5000 -> {
                    Log.d(TAG, "✅ 抢单: 起点近 + 终点远")
                    tap(tapCenterX, tapCenterY)
                }
                // 起点较近 (≤3.5km) 且 终点很远 (≥15km)
                startM in 0..3500 && endM >= 15000 -> {
                    Log.d(TAG, "✅ 抢单: 行程远")
                    tap(tapCenterX, tapCenterY)
                }
                else -> {
                    Log.d(TAG, "❌ 不抢: 距离不满足 (起点${startM}m 终点${endM}m)")
                    tap(100, 100)
                }
            }
        } else {
            Log.d(TAG, "❌ 不抢: 非即时用车 ($topType)")
            tap(100, 100)
        }
    }

    // ── 距离解析：xxxxkm → 米 ─────────────────────────────────

    /**
     * 将类似 "3.5km"、"15km" 的字符串转为米数
     * 例如: "3.5km" → 3500, "15km" → 15000
     */
    private fun kmToM(s: String): Int {
        if (s.isEmpty()) return 0
        // 去掉 "km" 后缀
        val numPart = s.replace("km", "", ignoreCase = true).trim()
        if (numPart.isEmpty()) return 0

        return try {
            // "3.5" → 3.5 → 3500
            (numPart.toFloat() * 1000).toInt()
        } catch (e: NumberFormatException) {
            Log.e(TAG, "距离解析失败: $s", e)
            0
        }
    }

    // ── 手势：点击 / 滑动 ─────────────────────────────────────

    /**
     * 在屏幕坐标 (x, y) 处执行点击
     */
    private fun tap(x: Int, y: Int) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 100L))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "点击完成 ($x, $y)")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.e(TAG, "点击取消 ($x, $y)")
            }
        }, null)
    }

    /**
     * 滑动手势
     */
    private fun swipe(xStart: Int, yStart: Int, xEnd: Int, yEnd: Int, durationMs: Long = 300) {
        val path = Path().apply {
            moveTo(xStart.toFloat(), yStart.toFloat())
            lineTo(xEnd.toFloat(), yEnd.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, null, null)
    }

    override fun onInterrupt() {
        Log.d(TAG, "服务中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "服务销毁")
    }
}