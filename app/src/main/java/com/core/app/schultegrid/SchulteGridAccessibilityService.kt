package com.core.app.schultegrid

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider.NewInstanceFactory.Companion.instance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max

class SchulteGridAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "SchulteGrid"
        @SuppressLint("StaticFieldLeak")
        private var instance: SchulteGridAccessibilityService? = null
        private var selectionListeners: MutableList<(Int, Int, Int, Int) -> Unit> = mutableListOf()
        private var delayChangeListeners: MutableList<(Int) -> Unit> = mutableListOf()

        // 搜索延迟，可在悬浮窗动态调整，默认50ms
        @Volatile
        var searchDelay: Int = 50
            set(value) {
                val validDelay = value.coerceIn(50, 999)
                field = validDelay
                Log.d(TAG, "设置搜索延迟: ${validDelay}ms")
                notifyDelayChangeListeners(validDelay)
            }

        // 是否启用每次点击数字的误差
        @Volatile
        var startWc: Boolean = false

        fun startTask() {
            Log.d(TAG, "外部调用 startTask()")
            instance?.startSchulteGridTask()
        }

        fun stopTask() {
            Log.d(TAG, "外部调用 stopTask()")
            instance?.stopSchulteGridTask()
        }

        fun setSelectionArea(left: Int, top: Int, right: Int, bottom: Int) {
            Log.d(TAG, "设置框选区域: Rect($left, $top, $right, $bottom)")
            instance?.setSelection(left, top, right, bottom)
        }

        fun clearSelectionArea() {
            Log.d(TAG, "清除框选区域")
            instance?.clearSelection()
        }

        fun addSelectionListener(listener: (Int, Int, Int, Int) -> Unit) {
            selectionListeners.add(listener)
        }

        private fun notifySelectionListeners(left: Int, top: Int, right: Int, bottom: Int) {
            selectionListeners.forEach { it(left, top, right, bottom) }
        }

        /**
         * 添加延迟变化监听器
         */
        fun addDelayChangeListener(listener: (Int) -> Unit) {
            delayChangeListeners.add(listener)
        }

        private fun notifyDelayChangeListeners(delay: Int) {
            delayChangeListeners.forEach { it(delay) }
        }
    }

    @Volatile
    private var isRunning = false
    private var currentNumber = 1
    private val handler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private lateinit var windowManager: WindowManager
    private var clickMarkers: MutableList<View> = mutableListOf()

    // 框选区域
    private var selectionRect: Rect? = null
    private var selectionView: View? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        Log.d(TAG, "收到事件: ${event.eventType}, 包名: ${event.packageName}")
        // 只在有外部触发时才执行
    }

    override fun onInterrupt() {
        Log.w(TAG, "onInterrupt 被调用")
        stopSchulteGridTask()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        Log.i(TAG, "舒尔特方格服务已连接")
        Toast.makeText(this, "舒尔特方格服务已连接", Toast.LENGTH_SHORT).show()
    }

    private fun startSchulteGridTask() {
        if (isRunning) {
            Log.w(TAG, "任务已经在运行中，忽略启动请求")
            return
        }
        Log.i(TAG, "===== 开始舒尔特方格任务 =====")
        isRunning = true
        currentNumber = 1
        searchForNextNumber()
    }

    private fun stopSchulteGridTask() {
        Log.i(TAG, "===== 停止舒尔特方格任务 =====")
        isRunning = false
        searchRunnable?.let { handler.removeCallbacks(it) }
        searchRunnable = null
        currentNumber = 1
    }

    private fun searchForNextNumber() {
        if (!isRunning) {
            Log.w(TAG, "任务已停止，停止搜索")
            return
        }

        searchRunnable?.let { handler.removeCallbacks(it) }

        searchRunnable = Runnable {
            Log.d(TAG, "开始搜索数字: $currentNumber")
            searchAndClickNumber(currentNumber)
        }
        // 使用动态延迟
        val delay = searchDelay.toLong()

        if (startWc) {
            val randomDelay = (delay + delay * 0.8 * Math.random()).toLong()
            Log.d(TAG, "启用误差，原延迟${delay}ms，实际搜索延迟: ${randomDelay}ms")
        } else {
            Log.d(TAG, "使用搜索延迟: ${delay}ms")
        }
        handler.postDelayed(searchRunnable!!, delay)
    }

    private fun searchAndClickNumber(targetNumber: Int) {
        if (!isRunning) {
            Log.w(TAG, "任务已停止，停止点击")
            return
        }

        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            Log.w(TAG, "无法获取 rootInActiveWindow，延迟后重试")
            handler.postDelayed({ searchForNextNumber() }, 200)
            return
        }

        Log.d(TAG, "获取到根节点，开始搜索数字: $targetNumber")

        // 搜索目标数字
        val targetNode = findNumberNode(rootNode, targetNumber.toString())

        if (targetNode != null) {
            // 找到数字，执行点击
            Log.i(TAG, "✓ 找到数字: $targetNumber")

            // 记录节点信息
            logNodeInfo(targetNode)

            // 尝试点击
            clickNode(targetNode)
            currentNumber++

            if (currentNumber > 50) {
                // 完成所有数字，重置
                Log.i(TAG, "★★★ 舒尔特方格完成！准备重新开始 ★★★")
                Toast.makeText(this, "舒尔特方格完成！准备重新开始", Toast.LENGTH_SHORT).show()
                currentNumber = 1
                stopSchulteGridTask()
                // 3秒后重新开始
                handler.postDelayed({
                    startSchulteGridTask()
                }, 3000)
            } else {
                // 继续搜索下一个数字
                searchForNextNumber()
            }
        } else {
            // 没找到，继续搜索
            Log.d(TAG, "✗ 未找到数字: $targetNumber，继续搜索...")
            handler.postDelayed({ searchForNextNumber() }, 100)
        }
    }

    private fun logNodeInfo(node: AccessibilityNodeInfo) {
        val actions = mutableListOf<String>()
        if (node.isClickable) actions.add("CLICKABLE")
        if (node.actionList.isNotEmpty()) {
            node.actionList.forEach {
                actions.add(it.id.toString())
            }
        }

        Log.d(
            TAG, "节点信息 - " +
                    "className: ${node.className}, " +
                    "text: ${node.text}, " +
                    "contentDescription: ${node.contentDescription}, " +
                    "isClickable: ${node.isClickable}, " +
                    "actions: $actions"
        )

        // 检查父节点是否可点击
        var parent = node.parent
        var level = 1
        while (parent != null && level <= 3) {
            if (parent.isClickable) {
                Log.d(
                    TAG,
                    "第 $level 级父节点可点击 - className: ${parent.className}, text: ${parent.text}"
                )
                break
            }
            parent = parent.parent
            level++
        }
    }

    private fun findNumberNode(
        node: AccessibilityNodeInfo?,
        targetText: String
    ): AccessibilityNodeInfo? {
        if (node == null) return null

        // 获取节点边界
        val nodeBounds = Rect()
        node.getBoundsInScreen(nodeBounds)

        // 如果设置了框选区域，只搜索框选区域内的节点
        if (selectionRect != null) {
            if (!isNodeInSelection(nodeBounds, selectionRect!!)) {
                // 节点不在框选区域内，跳过
                return null
            }
        }

        // 检查当前节点的文本是否匹配
        node.text?.toString()?.trim()?.let { text ->
            // 必须精确匹配纯数字，不能有其他文本
            if (text == targetText && isPureNumber(text)) {
                // 还要检查是否不是在"level"等文本中
                if (!isInLevelText(node, text)) {
                    Log.v(TAG, "匹配到节点(text): $text")
                    return node
                }
            }
        }

        // 检查contentDescription
        node.contentDescription?.toString()?.trim()?.let { text ->
            if (text == targetText && isPureNumber(text)) {
                if (!isInLevelText(node, text)) {
                    Log.v(TAG, "匹配到节点(contentDescription): $text")
                    return node
                }
            }
        }

        // 递归搜索子节点
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val result = findNumberNode(child, targetText)
            if (result != null) {
                return result
            }
        }

        return null
    }

    private fun isNodeInSelection(nodeBounds: Rect, selection: Rect): Boolean {
        // 检查节点是否在框选区域内（或者与框选区域有交集）
        return Rect.intersects(nodeBounds, selection)
    }

    private fun isPureNumber(text: String): Boolean {
        // 纯数字，长度1-2位（1-50）
        return text.matches(Regex("^\\d{1,2}$"))
    }

    private fun isInLevelText(node: AccessibilityNodeInfo, text: String): Boolean {
        // 检查当前节点的文本是否包含 "level" 等关键词
        val nodeText = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""

        if (nodeText.contains("level") || desc.contains("level")) {
            Log.v(TAG, "排除节点：包含 'level' - $text")
            return true
        }

        // 检查父节点
        var parent = node.parent
        var level = 1
        while (parent != null && level <= 3) {
            val parentText = parent.text?.toString()?.lowercase() ?: ""
            val parentDesc = parent.contentDescription?.toString()?.lowercase() ?: ""

            if (parentText.contains("level") || parentDesc.contains("level")) {
                Log.v(TAG, "排除节点：父节点包含 'level' - $text (第 $level 级)")
                return true
            }
            parent = parent.parent
            level++
        }

        return false
    }

    private fun clickNode(node: AccessibilityNodeInfo) {
        scope.launch {
            try {
                val nodeBounds = Rect()
                node.getBoundsInScreen(nodeBounds)

                Log.d(TAG, "节点边界: $nodeBounds")

                // 尝试多个点击位置，从中心开始，然后尝试其他位置
                val clickPositions = mutableListOf<Pair<Float, Float>>()

                // 中心点 - 使用真正的中心，不做偏移
                val centerX = nodeBounds.left + nodeBounds.width() / 2f
                val centerY = nodeBounds.top + nodeBounds.height() / 2f
                clickPositions.add(Pair(centerX, centerY))

                // 添加更多备选点击位置
                clickPositions.add(Pair(centerX, nodeBounds.top + nodeBounds.height() * 0.3f)) // 偏上位置
                clickPositions.add(Pair(centerX, nodeBounds.top + nodeBounds.height() * 0.7f)) // 偏下位置
                clickPositions.add(Pair(nodeBounds.left + nodeBounds.width() * 0.3f, centerY)) // 偏左位置
                clickPositions.add(Pair(nodeBounds.left + nodeBounds.width() * 0.7f, centerY)) // 偏右位置

                var clickSuccess = false
                for (i in clickPositions.indices) {
                    if (!isRunning) {
                        Log.w(TAG, "任务已停止，停止点击")
                        break
                    }

                    val position = clickPositions[i]
                    val x = position.first
                    val y = position.second

                    Log.d(TAG, "尝试位置 $i: ($x, $y)")

                    // 显示点击标记
                    handler.post {
                        // showClickMarker(x, y, nodeBounds)
                    }

                    // 执行点击并等待完成
                    val success = performClickAndWait(x, y)

                    if (success) {
                        clickSuccess = true
                        Log.i(TAG, "✓ 位置 $i 点击成功")
                        break
                    } else {
                        Log.d(TAG, "✗ 位置 $i 点击失败，尝试下一个位置")
                    }

                    // 等待一小段时间再尝试下一个位置
                    delay(100)
                }

                if (!clickSuccess) {
                    Log.w(TAG, "所有点击位置都失败了，尝试使用 performAction")
                    // 备选方案：使用 AccessibilityNodeInfo 的 performAction
                    val actionSuccess = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "performAction 点击结果: $actionSuccess")
                }


            } catch (e: Exception) {
                Log.e(TAG, "点击失败: ${e.message}", e)
            }
        }

    }

    /**
     * 执行点击手势并等待完成
     *
     * @param x 点击的 X 坐标
     * @param y 点击的 Y 坐标
     * @return 点击是否成功完成
     */
    private fun performClickAndWait(x: Float, y: Float): Boolean {
        return try {
            val path = Path()
            path.moveTo(x, y)

            // 使用更短的点击持续时间，模拟真实点击
            val duration = 50L
            val stroke = GestureDescription.StrokeDescription(path, 0, duration)

            val gestureBuilder = GestureDescription.Builder()
            gestureBuilder.addStroke(stroke)

            // 使用 CountDownLatch 等待手势完成
            val latch = java.util.concurrent.CountDownLatch(1)
            var completed = false
            var cancelled = false

            val result = dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "手势执行完成")
                    completed = true
                    latch.countDown()
                    super.onCompleted(gestureDescription)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "手势执行被取消")
                    cancelled = true
                    latch.countDown()
                    super.onCancelled(gestureDescription)
                }
            }, null)

            if (!result) {
                Log.w(TAG, "dispatchGesture 返回 false，手势未能分发")
                return false
            }

            // 等待手势完成（最多等待 500ms）
            latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)

            Log.d(TAG, "手势结果 - completed: $completed, cancelled: $cancelled")
            completed && !cancelled
        } catch (e: Exception) {
            Log.e(TAG, "执行点击失败: ${e.message}", e)
            false
        }
    }

    private fun showClickMarker(x: Float, y: Float, bounds: Rect) {
        try {
            // 创建一个圆形的点击标记
            val marker = ImageView(this)
            val markerSize = 100 // 标记大小

            // 设置圆形的红色标记
            marker.setBackgroundColor(Color.TRANSPARENT)
            marker.setImageResource(android.R.drawable.ic_menu_add) // 使用系统图标

            val params = WindowManager.LayoutParams(
                markerSize,
                markerSize,
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )

            params.gravity = Gravity.TOP or Gravity.START
            params.x = x.toInt() - markerSize / 2
            params.y = y.toInt() - markerSize / 2

            windowManager.addView(marker, params)
            clickMarkers.add(marker)

            Log.d(TAG, "显示点击标记在: ($x, $y)")

            // 2秒后移除标记
            handler.postDelayed({
                try {
                    windowManager.removeView(marker)
                    clickMarkers.remove(marker)
                    Log.d(TAG, "移除点击标记")
                } catch (e: Exception) {
                    Log.e(TAG, "移除标记失败: ${e.message}")
                }
            }, 2000)
        } catch (e: Exception) {
            Log.e(TAG, "显示点击标记失败: ${e.message}", e)
        }
    }

    private fun findClickableParent(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null

        var parent = node.parent
        var level = 1
        while (parent != null && level <= 5) {
            if (parent.isClickable) {
                Log.d(TAG, "找到第 $level 级可点击父节点: ${parent.className}")
                return parent
            }
            parent = parent.parent
            level++
        }
        Log.d(TAG, "未找到可点击的父节点")
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "舒尔特方格服务已销毁")
        stopSchulteGridTask()
        // 移除所有点击标记
        clickMarkers.forEach { marker ->
            try {
                windowManager.removeView(marker)
            } catch (e: Exception) {
                Log.e(TAG, "移除标记失败: ${e.message}")
            }
        }
        clickMarkers.clear()
        // 移除框选视图
        selectionView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "移除框选视图失败: ${e.message}")
            }
        }
        scope.cancel()
        selectionView = null
        instance = null
    }

    private fun setSelection(left: Int, top: Int, right: Int, bottom: Int) {
        selectionRect = Rect(left, top, right, bottom)
        showSelectionView()
        notifySelectionListeners(left, top, right, bottom)
    }

    private fun clearSelection() {
        selectionRect = null
        selectionView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "移除框选视图失败: ${e.message}")
            }
        }
        selectionView = null
    }

    private fun showSelectionView() {
        try {
            // 移除旧的框选视图
            selectionView?.let {
                try {
                    windowManager.removeView(it)
                } catch (e: Exception) {
                    // 忽略
                }
            }

            val rect = selectionRect ?: return

            // 创建框选视图
            selectionView = View(this)
            val layoutParams = WindowManager.LayoutParams(
                rect.width(),
                rect.height(),
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )

            layoutParams.gravity = Gravity.TOP or Gravity.START
            layoutParams.x = rect.left
            layoutParams.y = rect.top

            windowManager.addView(selectionView, layoutParams)
            Log.d(TAG, "显示框选视图: $rect")
        } catch (e: Exception) {
            Log.e(TAG, "显示框选视图失败: ${e.message}", e)
        }
    }
}
