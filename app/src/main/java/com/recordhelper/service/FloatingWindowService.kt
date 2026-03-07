package com.recordhelper.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.recordhelper.analyzer.VideoPageAnalyzer
import com.recordhelper.analyzer.PublishTimeAnalyzer
import com.recordhelper.data.RecordRepository
import com.recordhelper.data.RecordStatus
import com.recordhelper.data.VideoRecordEntity
import kotlinx.coroutines.*

private const val TAG = "FloatingWindowService"

class FloatingWindowService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private lateinit var params: WindowManager.LayoutParams
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var repository: RecordRepository
    private val analyzer = VideoPageAnalyzer()
    private lateinit var statusText: TextView

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            repository = RecordRepository(this)
            setupFloatingWindow()
            Log.d(TAG, "Floating window created successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create floating window", e)
            Toast.makeText(this, "悬浮窗创建失败: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        return START_STICKY
    }

    private fun setupFloatingWindow() {
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
            text = "记录助手 ✅"
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
        var isDragging = false

        floatingView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) isDragging = true
                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager.updateViewLayout(floatingView, params)
                    true
                }
                else -> false
            }
        }
    }

    private fun onRecordClick() {
        scope.launch {
            statusText.text = "截屏中..."
            try {
                val bitmap = CaptureForegroundService.instance?.captureBitmap()
                if (bitmap == null) {
                    statusText.text = "截屏失败(无实例)"
                    return@launch
                }

                statusText.text = "分析中..."
                val result = analyzer.analyze(bitmap)

                if (result.isVideoPage) {
                    val ratioTag = if (result.meetsRatioCondition) "✅比例达标" else "⚠️比例不达标"
                    val record = VideoRecordEntity(
                        merchantName = result.merchantName,
                        interactionCount = result.interactionCount,
                        status = if (result.meetsRatioCondition) RecordStatus.SUCCESS else RecordStatus.PENDING
                    )
                    repository.insert(record)
                    statusText.text = "✅ ${result.merchantName}\n${result.interactionCount}\n$ratioTag"
                } else {
                    statusText.text = "❌ 非视频页面"
                }

                bitmap.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "Record error", e)
                statusText.text = "错误: ${e.message}"
            }
        }
    }

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
                    val result = analyzer.analyze(bitmap)
                    result.ocrText
                }

                val timeResult = PublishTimeAnalyzer.analyze(ocrText)
                if (timeResult.parsedTime.isNotEmpty()) {
                    statusText.text = "⏰ ${timeResult.parsedTime}"
                } else {
                    statusText.text = "未识别到时间"
                }

                bitmap.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "Time error", e)
                statusText.text = "错误: ${e.message}"
            }
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        scope.cancel()
        floatingView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
