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
 * 自动抢单无障碍服务 — 完整筛选逻辑
 *
 * 执行顺序（短路优先）：
 *   1. 弹窗「是否确认抢单」→ 自动点「确定」
 *   2. 屏幕出现「确认抢单」→ 扫描订单信息
 *   3. 关键词屏蔽：起点/终点包含屏蔽词 → ❌ 跳过
 *   4. 金额 >= 最低金额 且 距离 <= 最远距离 → ✅ 点击
 *
 * 解析方式：BFS 遍历 AccessibilityNodeInfo 树，
 * 正则匹配 "数字+元" / "数字+公里" / 全屏文字做关键词匹配
 */
class AutoClickService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoScriptTool"

        // ── 目标文字 ──
        private const val TEXT_CONFIRM_GRAB = "确认抢单"
        private const val TEXT_CONFIRM_DIALOG = "是否确认抢单"
        private const val TEXT_CONFIRM_BTN = "确定"

        // ── 正则 ──
        // "预计收入20元" / "20元" / "¥20.5" → 20.5
        private val REGEX_AMOUNT = Pattern.compile("""(\d+\.?\d*)\s*[元¥]""")
        // "5公里" / "接驾5公里" / "5.3km" → 5.3
        private val REGEX_DISTANCE = Pattern.compile("""(\d+\.?\d*)\s*(公里|km)""", Pattern.CASE_INSENSITIVE)

        // BFS 扫描上限
        private const val MAX_SCAN_NODES = 2000

        // 防抖
        private const val DEBOUNCE_MS = 2000L

        @Volatile
        var instance: AutoClickService? = null
            private set
    }

    private lateinit var prefs: SharedPreferences
    private var minAmount = MainActivity.DEFAULT_MIN_AMOUNT
    private var maxDistance = MainActivity.DEFAULT_MAX_DISTANCE
    private var blockKeywords = emptyList<String>()

    private var lastClickTime = 0L
    private var lastWindowId = -1
    private var orderAmount = 0f
    private var orderDistance = 0f

    // ── 生命周期 ───────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
        reloadPrefs()
        Log.i(TAG, "服务连接 — 最低:${minAmount}元  距离≤${maxDistance}公里  屏蔽:${blockKeywords.size}个")

        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
    }

    /** 每次事件触发重新读配置 */
    private fun reloadPrefs() {
        minAmount = prefs.getFloat(MainActivity.KEY_MIN_AMOUNT, MainActivity.DEFAULT_MIN_AMOUNT)
        maxDistance = prefs.getFloat(MainActivity.KEY_MAX_DISTANCE, MainActivity.DEFAULT_MAX_DISTANCE)

        val raw = prefs.getString(MainActivity.KEY_BLOCK_KEYWORDS, MainActivity.DEFAULT_BLOCK_KEYWORDS) ?: ""
        // 逗号、中文逗号、空格 做分隔，过滤空串
        blockKeywords = raw
            .split(Regex("""[，,\s]+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
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

    // ═══════════════ 主流程 ═══════════════════════════════════

    private fun process(root: AccessibilityNodeInfo) {
        reloadPrefs()

        // ── 第一步：弹窗确认 ──
        if (root.findAccessibilityNodeInfosByText(TEXT_CONFIRM_DIALOG).isNotEmpty()) {
            Log.d(TAG, "📋 弹窗: $TEXT_CONFIRM_DIALOG")
            findClickableByText(root, TEXT_CONFIRM_BTN)?.let {
                Log.d(TAG, "✅ 弹窗确定 → 金额=${orderAmount}元 距离=${orderDistance}公里")
                clickNode(it)
            }
            return
        }

        // ── 第二步：查找「确认抢单」按钮 ──
        val grabNode = findClickableByText(root, TEXT_CONFIRM_GRAB) ?: return

        // ── 第三步：全屏提取订单信息 ──
        // 一次 BFS 同时收集：金额、距离、全屏文字（给关键词匹配用）
        val scan = ScreenScan()
        bfsCollect(root, scan)

        orderAmount = scan.bestAmount
        orderDistance = scan.bestDistance
        val allText = scan.allTextSb.toString()

        Log.i(TAG, "══════ 订单 ══════")
        Log.i(TAG, "  金额: ${orderAmount}元  (最低: ${minAmount}元)")
        Log.i(TAG, "  距离: ${orderDistance}公里  (最远: ${maxDistance}公里)")
        if (blockKeywords.isNotEmpty()) {
            Log.i(TAG, "  屏蔽词: [${blockKeywords.joinToString(", ")}]")
        }

        // ═══ 第四步：关键词屏蔽 ═══
        //   全屏文本中是否包含任一屏蔽词
        //   「起点」和「终点」均为全屏文本的子集，
        //   不管 app 怎么布局，只要文字出现过就会被命中
        if (blockKeywords.isNotEmpty()) {
            for (kw in blockKeywords) {
                if (allText.contains(kw, ignoreCase = true)) {
                    Log.i(TAG, "❌ 跳过: 屏蔽关键词「$kw」命中")
                    return
                }
            }
        }

        // ═══ 第五步：金额 + 距离筛选 ═══
        if (orderAmount <= 0f || orderDistance <= 0f) {
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

    // ═══════════════ BFS 扫描 ═════════════════════════════════

    /**
     * 一次 BFS 同时收集：
     *   - bestAmount: 最大金额
     *   - bestDistance: 最小距离
     *   - allText: 所有节点的文本（用空格连接，用于关键词匹配）
     */
    private fun bfsCollect(root: AccessibilityNodeInfo, scan: ScreenScan) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var depth = 0

        while (queue.isNotEmpty() && scan.nodeCount < MAX_SCAN_NODES) {
            val len = queue.size
            repeat(len) {
                if (queue.isEmpty()) return
                val node = queue.removeFirst()
                scan.nodeCount++

                val text = node.text?.toString()?.trim() ?: ""
                val desc = node.contentDescription?.toString()?.trim() ?: ""

                for (t in listOf(text, desc)) {
                    if (t.isEmpty()) continue

                    // 收集全文本
                    scan.allTextSb.append(t).append(' ')

                    // 匹配金额
                    val am = REGEX_AMOUNT.matcher(t)
                    while (am.find()) {
                        val v = am.group(1)?.toFloatOrNull() ?: continue
                        if (v > scan.bestAmount) scan.bestAmount = v
                    }

                    // 匹配距离
                    val dm = REGEX_DISTANCE.matcher(t)
                    while (dm.find()) {
                        val v = dm.group(1)?.toFloatOrNull() ?: continue
                        if (v < scan.bestDistance) scan.bestDistance = v
                    }
                }

                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
            depth++
        }

        if (scan.bestAmount > 0f) Log.d(TAG, "  解析金额: ${scan.bestAmount}元 (扫${scan.nodeCount}节点)")
        if (scan.bestDistance != Float.MAX_VALUE) Log.d(TAG, "  解析距离: ${scan.bestDistance}公里 (扫${scan.nodeCount}节点)")
    }

    /** 一次扫描的结果容器，避免 BFS 方法里多个返回值 */
    private class ScreenScan {
        var bestAmount = 0f
        var bestDistance = Float.MAX_VALUE
        var nodeCount = 0
        val allTextSb = StringBuilder()

        val bestDistanceOrZero: Float get() = if (bestDistance == Float.MAX_VALUE) 0f else bestDistance
    }

    // ── 节点查找 ───────────────────────────────────────────────

    private fun findClickableByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val matches = root.findAccessibilityNodeInfosByText(text)
        matches.firstOrNull { it.isClickable }?.let { return it }
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

    // ── 点击 ───────────────────────────────────────────────────

    private fun clickNode(node: AccessibilityNodeInfo) {
        val now = System.currentTimeMillis()
        val wid = node.windowId

        if (wid == lastWindowId && now - lastClickTime < DEBOUNCE_MS) {
            Log.d(TAG, "⏳ 防抖跳过")
            return
        }
        lastClickTime = now
        lastWindowId = wid

        // 语义点击
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