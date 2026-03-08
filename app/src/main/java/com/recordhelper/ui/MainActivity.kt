package com.recordhelper.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
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

    // Step 3: MediaProjection 权限回调
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d(TAG, "MediaProjection result: code=${result.resultCode}, data=${result.data}")
        Toast.makeText(this, "截屏权限回调: code=${result.resultCode}", Toast.LENGTH_SHORT).show()
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            Toast.makeText(this, "✅ 截屏权限已获取，正在启动服务...", Toast.LENGTH_LONG).show()
            CaptureForegroundService.setProjectionData(result.resultCode, result.data!!)
            try {
                startCaptureService()
                Toast.makeText(this, "✅ 截屏服务已启动", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start capture service", e)
                Toast.makeText(this, "❌ 截屏服务启动失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
            try {
                startFloatingService()
                Toast.makeText(this, "✅ 悬浮窗服务已启动，请切换到抖音", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start floating service", e)
                Toast.makeText(this, "❌ 悬浮窗服务启动失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "❌ 截屏权限被拒绝", Toast.LENGTH_SHORT).show()
        }
    }

    // Step 2: 通知权限回调
    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d(TAG, "Notification permission granted: $granted")
        Toast.makeText(this, "通知权限: $granted，继续请求截屏...", Toast.LENGTH_SHORT).show()
        requestMediaProjection()
    }

    override fun onResume() {
        super.onResume()
        if (pendingStart) {
            val canOverlay = Settings.canDrawOverlays(this)
            Log.d(TAG, "onResume pendingStart=true, canOverlay=$canOverlay")
            if (canOverlay) {
                pendingStart = false
                Toast.makeText(this, "✅ 悬浮窗权限已获取", Toast.LENGTH_SHORT).show()
                requestNotificationPermission()
            } else {
                Toast.makeText(this, "⚠️ 悬浮窗权限未授予，请授予后返回", Toast.LENGTH_LONG).show()
            }
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
                    onStop = { stopServices() }
                )
            }
        }
    }

    private fun startFlow() {
        Toast.makeText(this, "开始启动流程...", Toast.LENGTH_SHORT).show()
        val canOverlay = Settings.canDrawOverlays(this)
        Toast.makeText(this, "悬浮窗权限: $canOverlay", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "startFlow: canOverlay=$canOverlay")

        if (!canOverlay) {
            pendingStart = true
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                Toast.makeText(this, "请授予悬浮窗权限后返回此应用", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "❌ 无法打开权限设置: ${e.message}", Toast.LENGTH_LONG).show()
            }
            return
        }
        requestNotificationPermission()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(this, "请求通知权限...", Toast.LENGTH_SHORT).show()
                notificationPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        Toast.makeText(this, "通知权限OK，请求截屏权限...", Toast.LENGTH_SHORT).show()
        requestMediaProjection()
    }

    private fun requestMediaProjection() {
        Toast.makeText(this, "请求截屏权限...", Toast.LENGTH_SHORT).show()
        try {
            val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjectionLauncher.launch(pm.createScreenCaptureIntent())
        } catch (e: Exception) {
            Toast.makeText(this, "❌ 请求截屏权限失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startCaptureService() {
        Log.d(TAG, "Starting CaptureForegroundService")
        val intent = Intent(this, CaptureForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun startFloatingService() {
        val canOverlay = Settings.canDrawOverlays(this)
        Log.d(TAG, "Starting FloatingWindowService, canDrawOverlays=$canOverlay")
        Toast.makeText(this, "启动悬浮窗服务, overlay权限=$canOverlay", Toast.LENGTH_SHORT).show()
        val intent = Intent(this, FloatingWindowService::class.java)
        // 用普通 startService，不需要 foreground service type
        startService(intent)
    }

    private fun stopServices() {
        stopService(Intent(this, FloatingWindowService::class.java))
        stopService(Intent(this, CaptureForegroundService::class.java))
        Toast.makeText(this, "服务已停止", Toast.LENGTH_SHORT).show()
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
            Text(
                text = record.merchantName.ifEmpty { "未知商家" },
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("互动: ${record.interactionCount}", style = MaterialTheme.typography.bodySmall)
                Text(record.analyzedPublishTime.ifEmpty { "时间未知" }, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = record.status.name,
                style = MaterialTheme.typography.labelSmall,
                color = if (record.status == com.recordhelper.data.RecordStatus.SUCCESS)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.error
            )
        }
    }
}
