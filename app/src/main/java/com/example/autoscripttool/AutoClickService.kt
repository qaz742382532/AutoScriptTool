package com.example.autoscripttool

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.SharedPreferences
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.regex.Pattern

/**
 * 自动抢单无障碍服务 — 带筛选逻辑
 *
 * 执行顺序：
 *   1. 屏幕出现「确认抢单」→ 开始解析订单信息
 *   2. 从屏幕提取金额（如 "20元"）和距离（如 "5公里"）
 *   3. 跟用户设定的最低金额 / 最远距离对比
 *   4. 满足条件 → 自动点击
 *   5. 出现「是否确认抢单」弹窗 → 自动点「确定」
 *
 * 解析方式：遍历 AccessibilityNodeInfo 树，用正则匹配 "数字+元" / "数字+公里"
 */
class AutoClickService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoScriptTool"

        // ── 目标文字 ──
        private const val TEXT_CONFIRM_GRAB = "确认抢单"
        private const val TEXT_CONFIRM_DIALOG = "是否确认抢单"
        private const val TEXT_CONFIRM_BTN = "确定"

        // ── 正则：从文字里抠数值 ──
        // "预计收入20元" / "20元" / "¥20.5" / "20.5元" → 20.5
        private val REGEX_AMOUNT = Pattern.compile("""(\d+\.?\d*)\s*[元¥]""")
        // "5公里" / "接驾5公里" / "距离5km" / "5.3km" → 5.3
        private val REGEX_DISTANCE = Pattern.compile("""(\d+\.?\d*)\s*(公里|km)""", Pattern.CASE_INSENSITIVE)

        // 最多扫描节点数（防止超大页面卡死）
        private const val MAX_SCAN_NODES = 2000

        // 防抖间隔
        private const val DEBOUNCE_MS = 2000L

        @Volatile
        var instance: AutoClickService? = null
            private set
    }

    private lateinit var prefs: SharedPreferences
    private var minAmount = MainActivity.DEFAULT_MIN_AMOUNT
    private var maxDistance = MainActivity.DEFAULT_MAX_DISTANCE

    private var lastClickTime = 0L
    private var lastWindowId = -1
    // 本次订单的解析值
    private var orderAmount = 0f
    private var orderDistance = 0f

    // ── 生命周期 ───────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
        reloadPrefs()
        Log.i(TAG, "服务连接 — 最低金额:${minAmount}元  接驾≤${maxDistance}公里")

        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
    }

    /** 每次事件触发时重新读配置（用户可能改了设置） */
    private fun reloadPrefs() {
        minAmount = prefs.getFloat(MainActivity.KEY_MIN_AMOUNT, MainActivity.DEFAULT_MIN_AMOUNT)
        maxDistance = prefs.getFloat(MainActivity.KEY_MAX_DISTANCE, MainActivity.DEFAULT_MAX_DISTANCE)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val root = rootInActiveWindow ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                process(root)
            }
        }
    }

    // ── 主流程 ─────────────────────────────────────────────────

    private fun process(root: AccessibilityNodeInfo) {
        // 刷新配置
        reloadPrefs()

        // ═══ 第一步：弹窗 "是否确认抢单" ═══
        val dialogNode = root.findAccessibilityNodeInfosByText(TEXT_CONFIRM_DIALOG)
        if (dialogNode.isNotEmpty()) {
            Log.d(TAG, "📋 弹窗: $TEXT_CONFIRM_DIALOG")
            val okNode = findClickableByText(root, TEXT_CONFIRM_BTN)
            if (okNode != null) {
                Log.d(TAG, "✅ 弹窗确定 → 点击金额=${orderAmount}元 距离=${orderDistance}公里")
                clickNode(okNode)
            }
            return
        }

        // ═══ 第二步：查找 "确认抢单" 按钮 ═══
        val grabNode = findClickableByText(root, TEXT_CONFIRM_GRAB) ?: return

        // ═══ 第三步：全屏扫描，提取金额和距离 ═══
        orderAmount = scanAmount(root)
        orderDistance = scanDistance(root)

        Log.i(TAG, "══════ 订单 ══════")
        Log.i(TAG, "  金额: ${orderAmount}元  (最低要求: ${minAmount}元)")
        Log.i(TAG, "  距离: ${orderDistance}公里  (最远允许: ${maxDistance}公里)")

        // ═══ 第四步：筛选判断 ═══
        if (orderAmount <= 0f || orderDistance <= 0f) {
            // 没解析出来 → 谨慎起见不抢
            Log.w(TAG, "❌ 跳过: 未能解析金额或距离")
            return
        }

        if (orderAmount < minAmount) {
            Log.i(TAG, "❌ 跳过: 金额${orderAmount}元 < 最低${minAmount}元")
            return
        }

        if (orderDistance > maxDistance) {
            Log.i(TAG, "❌ 跳过: 距离${orderDistance}公里 > 最远${maxDistance}公里")
            return
        }

        Log.i(TAG, "✅ 满足条件 → 抢单！")
        clickNode(grabNode)
    }

    // ── 屏幕扫描：金额 ─────────────────────────────────────────

    /**
     * 遍历节点树，用正则匹配所有包含 "数字+元" 的文字，
     * 取最大金额（因为"确认抢单"按钮附近可能还有预估收入等文本）
     *
     * 你也可以改成取第一条匹配 or 取「预计收入」后第一个金额
     * 取决于实际 App 布局——下面两种策略都有
     */
    private fun scanAmount(root: AccessibilityNodeInfo): Float {
        var best = 0f
        var scanCount = 0

        // 策略一：遍历所有节点，正则匹配
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && scanCount < MAX_SCAN_NODES) {
            val node = queue.removeFirst()
            scanCount++

            val text = node.text?.toString() ?: ""
            val contentDesc = node.contentDescription?.toString() ?: ""

            for (t in listOf(text, contentDesc)) {
                if (t.isBlank()) continue
                val m = REGEX_AMOUNT.matcher(t)
                while (m.find()) {
                    val v = m.group(1)?.toFloatOrNull() ?: continue
                    if (v > best) best = v
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        if (best > 0f) Log.d(TAG, "  解析金额: $best 元 (扫描 $scanCount 个节点)")
        return best
    }

    // ── 屏幕扫描：距离 ─────────────────────────────────────────

    /**
     * 提取接驾距离（最小距离，取最短接驾）
     *
     * 正则匹配 "数字+公里/km"，取最小值
     * 因为屏幕上可能同时有起点距离、终点距离、接驾距离
     */
    private fun scanDistance(root: AccessibilityNodeInfo): Float {
        var best = Float.MAX_VALUE
        var scanCount = 0

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && scanCount < MAX_SCAN_NODES) {
            val node = queue.removeFirst()
            scanCount++

            val text = node.text?.toString() ?: ""
            val contentDesc = node.contentDescription?.toString() ?: ""

            for (t in listOf(text, contentDesc)) {
                if (t.isBlank()) continue
                val m = REGEX_DISTANCE.matcher(t)
                while (m.find()) {
                    val v = m.group(1)?.toFloatOrNull() ?: continue
                    if (v < best) best = v
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        val result = if (best == Float.MAX_VALUE) 0f else best
        if (result > 0f) Log.d(TAG, "  解析距离: $result 公里 (扫描 $scanCount 个节点)")
        return result
    }

    // ── 节点查找 ───────────────────────────────────────────────

    private fun findClickableByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val matches = root.findAccessibilityNodeInfosByText(text)
        // 先看直接可点击
        matches.firstOrNull { it.isClickable }?.let { return it }
        // 再看可点击父节点
        for (m in matches) {
            findClickableAncestor(m)?.let { return it }
        }
        return null
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node.parent
        while (cur != null) {
            if (cur.isClickable) return cur
            cur = cur.parent
        }
        return null
    }

    // ── 手势点击 ───────────────────────────────────────────────

    private fun clickNode(node: AccessibilityNodeInfo) {
        val now = System.currentTimeMillis()
        val wid = node.windowId

        // 同一个窗口 2 秒内不重复处理
        if (wid == lastWindowId && now - lastClickTime < DEBOUNCE_MS) {
            Log.d(TAG, "⏳ 防抖跳过")
            return
        }
        lastClickTime = now
        lastWindowId = wid

        // 语义点击优先
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.d(TAG, "✅ ACTION_CLICK")
            return
        }

        // 手势兜底
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() > 0 && bounds.height() > 0) {
            val cx = bounds.centerX()
            val cy = bounds.centerY()
            val path = Path().apply { moveTo(cx.toFloat(), cy.toFloat()) }
            dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, 100L))
                    .build(),
                null, null
            )
            Log.d(TAG, "✅ GESTURE_CLICK ($cx, $cy)")
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "服务中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "服务销毁")
    }
}