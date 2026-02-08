package com.core.app.schultegrid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.core.app.schultegrid.SchulteGridAccessibilityService.Companion.TAG

class FloatingWindowService : Service() {


    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var isRunning = false
    private var selectionView: View? = null
    private var isSelecting = false
    private var selectionStartX = 0
    private var selectionStartY = 0
    private var selectionOverlay: View? = null  // 框选时的覆盖层

    // 悬浮窗参数，用于动态更新
    private lateinit var windowParams: WindowManager.LayoutParams

    // 透明模式状态
    private var isTransparentMode = false

    // 原始背景颜色，用于恢复
    private var originalBackgroundColor: Int = Color.parseColor("#E6FFFFFF")

    companion object {
        private const val CHANNEL_ID = "schulte_grid_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_TOGGLE = "com.core.app.schultegrid.TOGGLE"
        private const val ACTION_STOP = "com.core.app.schultegrid.STOP"

        // 透明模式下的背景透明度 (10% 不透明)
        private const val TRANSPARENT_ALPHA = 0.1f
        // 正常模式下的背景透明度 (90% 不透明)
        private const val NORMAL_ALPHA = 0.9f
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "悬浮窗服务 onCreate")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        createFloatingWindow()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "悬浮窗服务 onStartCommand, action: ${intent?.action}")
        intent?.action?.let { action ->
            when (action) {
                ACTION_TOGGLE -> toggleRunning()
                ACTION_STOP -> stopSelf()
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "舒尔特方格服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "舒尔特方格悬浮窗服务"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder.setContentTitle("舒尔特方格助手")
            .setContentText("悬浮窗运行中")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)

        return builder.build()
    }

    private fun createFloatingWindow() {
        Log.d(TAG, "创建悬浮窗")
        windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        windowParams.gravity = Gravity.TOP or Gravity.START
        windowParams.x = 100
        windowParams.y = 200

        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_window, null)

        val btnStart = floatingView?.findViewById<Button>(R.id.btn_start)
        val tvStatus = floatingView?.findViewById<TextView>(R.id.tv_status)
        val tvSelection = floatingView?.findViewById<TextView>(R.id.tv_selection)
        val btnClose = floatingView?.findViewById<Button>(R.id.btn_close)
        val btnSelect = floatingView?.findViewById<Button>(R.id.btn_select)
        val btnClearSelection = floatingView?.findViewById<Button>(R.id.btn_clear_selection)
        val btnToggleAlpha = floatingView?.findViewById<Button>(R.id.btn_toggle_alpha)
        val seekBarDelay = floatingView?.findViewById<SeekBar>(R.id.seekbar_delay)
        val tvDelayValue = floatingView?.findViewById<TextView>(R.id.tv_delay_value)
        val layoutContainer = floatingView?.findViewById<LinearLayout>(R.id.layout_container)

        // 保存原始背景颜色
        layoutContainer?.let {
            originalBackgroundColor = (it.background as? android.graphics.drawable.ColorDrawable)?.color
                ?: Color.parseColor("#E6FFFFFF")
        }

        // 初始化延迟设置
        val currentDelay = SchulteGridAccessibilityService.searchDelay
        seekBarDelay?.progress = currentDelay
        tvDelayValue?.text = "${currentDelay}ms"

        // 延迟变化监听
        seekBarDelay?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val delay = progress.coerceIn(50, 999)
                tvDelayValue?.text = "${delay}ms"
                if (fromUser) {
                    SchulteGridAccessibilityService.searchDelay = delay
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let {
                    val delay = it.progress.coerceIn(50, 999)
                    SchulteGridAccessibilityService.searchDelay = delay
                    Log.d(TAG, "用户设置搜索延迟: ${delay}ms")
                }
            }
        })

        // 无障碍服务延迟变化监听
        SchulteGridAccessibilityService.addDelayChangeListener { delay ->
            handler.post {
                seekBarDelay?.progress = delay
                tvDelayValue?.text = "${delay}ms"
            }
        }

        // 透明/不透明切换
        btnToggleAlpha?.setOnClickListener {
            isTransparentMode = !isTransparentMode
            updateTransparencyMode()
        }

        btnStart?.setOnClickListener {
            Log.d(TAG, "点击开始/停止按钮")
            toggleRunning()
        }

        btnClose?.setOnClickListener {
            if (!isTransparentMode){
                Log.d(TAG, "点击关闭悬浮窗")
                stopSelf()
            }
        }

        btnSelect?.setOnClickListener {
            if (!isTransparentMode){
                Log.d(TAG, "点击框选区域")
                startSelection()
            }
        }

        btnClearSelection?.setOnClickListener {
            if (!isTransparentMode){
                Log.d(TAG, "点击清除框选")
                clearSelection()
                tvSelection?.text = "框选: 未设置"
            }
        }

        // 框选状态监听
        SchulteGridAccessibilityService.addSelectionListener { left, top, right, bottom ->
            tvSelection?.text = "框选: 已设置 ($left, $top)"
        }

        // 添加拖动功能
        floatingView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = windowParams.x
                        initialY = windowParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        windowParams.x = initialX + (event.rawX - initialTouchX).toInt()
                        windowParams.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(floatingView, windowParams)
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(floatingView, windowParams)
        Log.i(TAG, "悬浮窗创建成功")
    }

    /**
     * 更新悬浮窗透明模式
     * 透明模式下：整个悬浮窗几乎完全透明，但按钮仍然可以点击
     */
    private fun updateTransparencyMode() {
        val layoutContainer = floatingView?.findViewById<LinearLayout>(R.id.layout_container)
        val btnStart = floatingView?.findViewById<Button>(R.id.btn_start)
        val btnSelect = floatingView?.findViewById<Button>(R.id.btn_select)
        val btnClearSelection = floatingView?.findViewById<Button>(R.id.btn_clear_selection)
        val btnClose = floatingView?.findViewById<Button>(R.id.btn_close)
        val btnToggleAlpha = floatingView?.findViewById<Button>(R.id.btn_toggle_alpha)
        val tvTitle = floatingView?.findViewById<TextView>(R.id.tv_title)
        val tvStatus = floatingView?.findViewById<TextView>(R.id.tv_status)
        val tvSelection = floatingView?.findViewById<TextView>(R.id.tv_selection)
        val tvDelayLabel = floatingView?.findViewById<TextView>(R.id.tv_delay_label)
        val tvDelayValue = floatingView?.findViewById<TextView>(R.id.tv_delay_value)
        val seekBarDelay = floatingView?.findViewById<SeekBar>(R.id.seekbar_delay)

        if (isTransparentMode) {
            // 透明模式 - 所有内容几乎完全透明
            layoutContainer?.setBackgroundColor(Color.TRANSPARENT) // 完全透明背景

            // 所有按钮透明但可点击
            btnStart?.background?.alpha = 0
            btnStart?.text = ""

            btnSelect?.background?.alpha = 0
            btnSelect?.text = ""

            btnClearSelection?.background?.alpha = 0
            btnClearSelection?.text = ""

            btnClose?.background?.alpha = 0
            btnClose?.text = ""

            // 保留一个几乎透明的恢复按钮（方便用户找到位置）
            btnToggleAlpha?.text = ""
            btnToggleAlpha?.setBackgroundColor(Color.TRANSPARENT)

            // 所有文字透明
            tvTitle?.alpha = 0f
            tvStatus?.alpha = 0f
            tvSelection?.alpha = 0f
            tvDelayLabel?.alpha = 0f
            tvDelayValue?.alpha = 0f
            seekBarDelay?.alpha = 0f

            // 屏蔽SeekBar
            seekBarDelay?.isEnabled = false

            Log.d(TAG, "切换到完全透明模式")
        } else {
            // 正常模式 - 恢复所有可见性
            layoutContainer?.setBackgroundColor(Color.argb(230, 255, 255, 255)) // 90% 透明度

            // 恢复按钮背景和文字
            btnStart?.background?.alpha = 255
            btnStart?.text = if (isRunning) "停止" else "开始"
            btnStart?.setBackgroundColor(if (isRunning) 0xFFFF4444.toInt() else 0xFF4CAF50.toInt())

            btnSelect?.background?.alpha = 255
            btnSelect?.text = "框选区域"
            btnSelect?.setBackgroundColor(0xFF2196F3.toInt())

            btnClearSelection?.background?.alpha = 255
            btnClearSelection?.text = "清除框选"
            btnClearSelection?.setBackgroundColor(0xFFFF9800.toInt())

            btnClose?.background?.alpha = 255
            btnClose?.text = "×"
            btnClose?.setBackgroundColor(0xFFFF4444.toInt())

            btnToggleAlpha?.text = "透明"
            btnToggleAlpha?.setBackgroundColor(0xFF666666.toInt())

            // 恢复所有文字可见性
            tvTitle?.alpha = 1f
            tvStatus?.alpha = 1f
            tvStatus?.text = "状态: ${if (isRunning) "运行中" else "已停止"}"
            tvSelection?.alpha = 1f
            tvDelayLabel?.alpha = 1f
            tvDelayValue?.alpha = 1f
            seekBarDelay?.alpha = 1f

            // 允许SeekBar
            seekBarDelay?.isEnabled = true
            Log.d(TAG, "切换到正常模式")
        }
    }

    private fun startSelection() {
        Log.d(TAG, "开始框选")
        isSelecting = true

        // 先清除旧的框选视图
        SchulteGridAccessibilityService.clearSelectionArea()

        // 创建全屏透明视图用于捕获触摸事件
        val fullscreenParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        selectionView = android.view.View(this)
        selectionView?.setBackgroundColor(Color.argb(30, 255, 0, 0))

        selectionView?.setOnTouchListener(object : View.OnTouchListener {
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                if (!isSelecting) return false

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        selectionStartX = event.rawX.toInt()
                        selectionStartY = event.rawY.toInt()
                        Log.d(TAG, "框选起点: ($selectionStartX, $selectionStartY)")
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val endX = event.rawX.toInt()
                        val endY = event.rawY.toInt()

                        val left = minOf(selectionStartX, endX)
                        val top = minOf(selectionStartY, endY)
                        val right = maxOf(selectionStartX, endX)
                        val bottom = maxOf(selectionStartY, endY)

                        // 确保框选区域足够大
                        if (right - left > 50 && bottom - top > 50) {
                            Log.d(TAG, "框选区域: ($left, $top, $right, $bottom)")
                            SchulteGridAccessibilityService.setSelectionArea(left, top, right, bottom)

                            val tvSelection = floatingView?.findViewById<TextView>(R.id.tv_selection)
                            tvSelection?.text = "框选: 已设置"
                        } else {
                            Log.w(TAG, "框选区域太小，已忽略")
                            SchulteGridAccessibilityService.clearSelectionArea()

                            val tvSelection = floatingView?.findViewById<TextView>(R.id.tv_selection)
                            tvSelection?.text = "框选: 未设置"
                        }

                        endSelection()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        // 实时更新框选区域显示
                        val currentX = event.rawX.toInt()
                        val currentY = event.rawY.toInt()

                        val left = minOf(selectionStartX, currentX)
                        val top = minOf(selectionStartY, currentY)
                        val right = maxOf(selectionStartX, currentX)
                        val bottom = maxOf(selectionStartY, currentY)

                        val width = right - left
                        val height = bottom - top

                        if (selectionOverlay != null) {
                            windowManager.removeView(selectionOverlay)
                        }

                        val overlayParams = WindowManager.LayoutParams(
                            width,
                            height,
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            } else {
                                @Suppress("DEPRECATION")
                                WindowManager.LayoutParams.TYPE_PHONE
                            },
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                            PixelFormat.TRANSLUCENT
                        )
                        overlayParams.gravity = Gravity.TOP or Gravity.START
                        overlayParams.x = left
                        overlayParams.y = top

                        selectionOverlay = android.view.View(this@FloatingWindowService)
                        selectionOverlay?.setBackgroundColor(Color.argb(100, 255, 0, 0))
                        windowManager.addView(selectionOverlay, overlayParams)
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        Log.d(TAG, "框选取消")
                        endSelection()
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(selectionView, fullscreenParams)
        Log.i(TAG, "框选视图已创建，请在屏幕上拖动选择区域")
    }

    private fun endSelection() {
        Log.d(TAG, "结束框选")
        isSelecting = false

        selectionView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "移除框选视图失败: ${e.message}")
            }
        }
        selectionView = null

        selectionOverlay?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "移除覆盖层失败: ${e.message}")
            }
        }
        selectionOverlay = null
    }

    private fun clearSelection() {
        Log.d(TAG, "清除框选区域")
        SchulteGridAccessibilityService.clearSelectionArea()
    }

    private fun toggleRunning() {
        isRunning = !isRunning

        val btnStart = floatingView?.findViewById<Button>(R.id.btn_start)
        val tvStatus = floatingView?.findViewById<TextView>(R.id.tv_status)

        // 如果处于透明模式，不更新UI（保持透明）
        if (isTransparentMode) {
            if (isRunning) {
                android.util.Log.d("SchulteGrid", "悬浮窗点击: 启动任务（透明模式）")
                SchulteGridAccessibilityService.startTask()
            } else {
                android.util.Log.d("SchulteGrid", "悬浮窗点击: 停止任务（透明模式）")
                SchulteGridAccessibilityService.stopTask()
            }
            return
        }

        if (isRunning) {
            btnStart?.text = "停止"
            btnStart?.setBackgroundColor(0xFFFF4444.toInt())
            tvStatus?.text = "状态: 运行中"
            android.util.Log.d("SchulteGrid", "悬浮窗点击: 启动任务")
            SchulteGridAccessibilityService.startTask()
        } else {
            btnStart?.text = "开始"
            btnStart?.setBackgroundColor(0xFF4CAF50.toInt())
            tvStatus?.text = "状态: 已停止"
            android.util.Log.d("SchulteGrid", "悬浮窗点击: 停止任务")
            SchulteGridAccessibilityService.stopTask()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "悬浮窗服务 onDestroy")
        floatingView?.let {
            windowManager.removeView(it)
        }
        selectionView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "移除框选视图失败: ${e.message}")
            }
        }
        selectionOverlay?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "移除覆盖层失败: ${e.message}")
            }
        }
        SchulteGridAccessibilityService.clearSelectionArea()
        SchulteGridAccessibilityService.stopTask()
    }

    // Handler for UI updates
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
}
