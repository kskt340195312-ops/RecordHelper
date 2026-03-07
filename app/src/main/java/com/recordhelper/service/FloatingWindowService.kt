package com.recordhelper.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.recordhelper.analyzer.VideoPageAnalyzer
import com.recordhelper.analyzer.PublishTimeAnalyzer
import com.recordhelper.data.RecordRepository
import com.recordhelper.data.RecordStatus
import com.recordhelper.data.VideoRecordEntity
import kotlinx.coroutines.*

/**
 * 悬浮窗服务
 * 提供可拖动的悬浮窗，两个按钮分别触发监测和补时间流程
 */
class FloatingWindowService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var params: WindowManager.LayoutParams
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var repository: RecordRepository
    private val analyzer = VideoPageAnalyzer()
    private lateinit var statusText: TextView

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        repository = RecordRepository(this)
        setupFloatingWindow()
    }

    private fun setupFloatingWindow() {
        // 创建悬浮窗布局
        floatingView = createFloatingLayout()

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }

        windowManager.addView(floatingView, params)
        setupDrag()
    }

    private fun createFloatingLayout(): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xDD333333.toInt())
            setPadding(24, 16, 24, 16)
        }

        statusText = TextView(this).apply {
            text = "记录助手"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
        }
        layout.addView(statusText)

        val btnRecord = Button(this).apply {
            text = "📷 截屏记录"
            textSize = 12f
            setOnClickListener { onRecordClick() }
        }
        layout.addView(btnRecord)

        val btnTime = Button(this).apply {
            text = "⏰ 补充时间"
            textSize = 12f
            setOnClickListener { onTimeClick() }
        }
        layout.addView(btnTime)

        return layout
    }

    private fun setupDrag() {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        floatingView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(floatingView, params)
                    true
                }
                else -> false
            }
        }
    }

    /**
     * 截屏记录按钮点击：截屏 → 分析 → 存储
     */
    private fun onRecordClick() {
        scope.launch {
            statusText.text = "截屏中..."
            try {
                val bitmap = CaptureForegroundService.instance?.captureBitmap()
                if (bitmap == null) {
                    statusText.text = "截屏失败"
                    return@launch
                }

                statusText.text = "分析中..."
                val result = analyzer.analyze(bitmap)

                if (result.isVideoPage) {
                    val record = VideoRecordEntity(
                        merchantName = result.merchantName,
                        interactionCount = result.interactionCount,
                        status = RecordStatus.SUCCESS
                    )
                    repository.insert(record)
                    statusText.text = "✅ ${result.merchantName}"
                } else {
                    statusText.text = "❌ 非视频页面"
                }

                bitmap.recycle()
            } catch (e: Exception) {
                statusText.text = "错误: ${e.message}"
            }
        }
    }

    /**
     * 补充时间按钮点击：截屏 → OCR → 解析时间 → 更新最近记录
     */
    private fun onTimeClick() {
        scope.launch {
            statusText.text = "截屏中..."
            try {
                val bitmap = CaptureForegroundService.instance?.captureBitmap()
                if (bitmap == null) {
                    statusText.text = "截屏失败"
                    return@launch
                }

                statusText.text = "识别时间..."
                val ocrText = withContext(Dispatchers.Default) {
                    // 简单复用 analyzer 的 OCR 能力
                    val result = analyzer.analyze(bitmap)
                    result.merchantName // 这里需要获取完整 OCR 文本，简化处理
                }

                val timeResult = PublishTimeAnalyzer.analyze(ocrText)
                if (timeResult.parsedTime.isNotEmpty()) {
                    statusText.text = "⏰ ${timeResult.parsedTime}"
                } else {
                    statusText.text = "未识别到时间"
                }

                bitmap.recycle()
            } catch (e: Exception) {
                statusText.text = "错误: ${e.message}"
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
