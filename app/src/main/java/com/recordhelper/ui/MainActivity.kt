package com.recordhelper.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.recordhelper.data.RecordRepository
import com.recordhelper.data.VideoRecordEntity
import com.recordhelper.export.ExcelExporter
import com.recordhelper.service.CaptureForegroundService
import com.recordhelper.service.FloatingWindowService

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {

    private lateinit var repository: RecordRepository
    private var pendingStart = false
    private var floatingView: android.view.View? = null

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            CaptureForegroundService.setProjectionData(result.resultCode, result.data!!)
            try {
                val intent = Intent(this, CaptureForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (e: Exception) {
                Toast.makeText(this, "截屏服务启动失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
            // 直接在 Activity 中创建悬浮窗，不通过 Service
            try {
                showFloatingWindow()
            } catch (e: Exception) {
                Toast.makeText(this, "悬浮窗创建失败: ${e.message}", Toast.LENGTH_LONG).show()
                Log.e(TAG, "showFloatingWindow failed", e)
            }
        } else {
            Toast.makeText(this, "需要截屏权限", Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> requestMediaProjection() }

    override fun onResume() {
        super.onResume()
        if (pendingStart && Settings.canDrawOverlays(this)) {
            pendingStart = false
            requestNotificationPermission()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = RecordRepository(this)
        setContent {
            MaterialTheme {
                MainScreen(
                    repository = repository,
                    onStart = { startFlow() },
                    onStop = { stopAll() }
                )
            }
        }
    }

    private fun startFlow() {
        if (!Settings.canDrawOverlays(this)) {
            pendingStart = true
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Toast.makeText(this, "请授予悬浮窗权限后返回", Toast.LENGTH_LONG).show()
            return
        }
        requestNotificationPermission()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        requestMediaProjection()
    }

    private fun requestMediaProjection() {
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(pm.createScreenCaptureIntent())
    }

    /**
     * 直接在 Activity 中创建悬浮窗
     * 不依赖 Service，确保悬浮窗一定能显示
     */
    private fun showFloatingWindow() {
        if (floatingView != null) return // 已经显示了

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 300
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xEE333333.toInt())
            setPadding(30, 20, 30, 20)
        }

        val statusTv = TextView(this).apply {
            text = "记录助手 ✅"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
        }
        layout.addView(statusTv)

        val btnRecord = Button(this).apply {
            text = "📷 截屏记录"
            textSize = 13f
        }
        layout.addView(btnRecord)

        val btnTime = Button(this).apply {
            text = "⏰ 补充时间"
            textSize = 13f
        }
        layout.addView(btnTime)

        // 拖拽支持
        var initialX = 0; var initialY = 0
        var initialTouchX = 0f; var initialTouchY = 0f
        layout.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    initialTouchX = event.rawX; initialTouchY = event.rawY
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    wm.updateViewLayout(layout, params)
                    true
                }
                else -> false
            }
        }

        // 截屏记录按钮
        btnRecord.setOnClickListener {
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                statusTv.text = "截屏中..."
                try {
                    val bitmap = CaptureForegroundService.instance?.captureBitmap()
                    if (bitmap == null) {
                        statusTv.text = "截屏失败"
                        return@launch
                    }
                    statusTv.text = "分析中..."
                    val analyzer = com.recordhelper.analyzer.VideoPageAnalyzer()
                    val result = analyzer.analyze(bitmap)
                    if (result.isVideoPage) {
                        val ratioTag = if (result.meetsRatioCondition) "✅达标" else "⚠️不达标"
                        val record = VideoRecordEntity(
                            merchantName = result.merchantName,
                            interactionCount = result.interactionCount,
                            status = if (result.meetsRatioCondition)
                                com.recordhelper.data.RecordStatus.SUCCESS
                            else com.recordhelper.data.RecordStatus.PENDING
                        )
                        repository.insert(record)
                        statusTv.text = "✅${result.merchantName}\n${result.interactionCount}\n$ratioTag"
                    } else {
                        statusTv.text = "❌非视频页"
                    }
                    bitmap.recycle()
                } catch (e: Exception) {
                    statusTv.text = "错误:${e.message}"
                }
            }
        }

        // 补充时间按钮
        btnTime.setOnClickListener {
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                statusTv.text = "截屏中..."
                try {
                    val bitmap = CaptureForegroundService.instance?.captureBitmap()
                    if (bitmap == null) { statusTv.text = "截屏失败"; return@launch }
                    statusTv.text = "识别时间..."
                    val analyzer = com.recordhelper.analyzer.VideoPageAnalyzer()
                    val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        analyzer.analyze(bitmap)
                    }
                    val timeResult = com.recordhelper.analyzer.PublishTimeAnalyzer.analyze(result.ocrText)
                    statusTv.text = if (timeResult.parsedTime.isNotEmpty()) "⏰${timeResult.parsedTime}" else "未识别到时间"
                    bitmap.recycle()
                } catch (e: Exception) { statusTv.text = "错误:${e.message}" }
            }
        }

        wm.addView(layout, params)
        floatingView = layout
        Toast.makeText(this, "悬浮窗已显示，可切换到抖音", Toast.LENGTH_LONG).show()
    }

    private fun stopAll() {
        floatingView?.let {
            try {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it)
            } catch (_: Exception) {}
            floatingView = null
        }
        try { stopService(Intent(this, FloatingWindowService::class.java)) } catch (_: Exception) {}
        try { stopService(Intent(this, CaptureForegroundService::class.java)) } catch (_: Exception) {}
        Toast.makeText(this, "已停止", Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    repository: RecordRepository,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val context = LocalContext.current
    val records by repository.getAllRecords().collectAsState(initial = emptyList())
    var isRunning by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("记录助手") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (isRunning) onStop() else onStart()
                        isRunning = !isRunning
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isRunning) "停止服务" else "启动服务")
                }
                Button(
                    onClick = {
                        val file = ExcelExporter.export(context, records)
                        if (file != null) {
                            Toast.makeText(context, "已导出: ${file.name}", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, "导出失败", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("导出 Excel")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text("共 ${records.size} 条记录", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(records) { record -> RecordCard(record) }
            }
        }
    }
}

@Composable
fun RecordCard(record: VideoRecordEntity) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(record.merchantName.ifEmpty { "未知商家" }, style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("互动: ${record.interactionCount}", style = MaterialTheme.typography.bodySmall)
                Text(record.analyzedPublishTime.ifEmpty { "时间未知" }, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = record.status.name,
                style = MaterialTheme.typography.labelSmall,
                color = if (record.status == com.recordhelper.data.RecordStatus.SUCCESS)
                    MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }
    }
}
