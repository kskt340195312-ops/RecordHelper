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
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            Log.d(TAG, "MediaProjection granted")
            CaptureForegroundService.setProjectionData(result.resultCode, result.data!!)
            startCaptureService()
            startFloatingService()
            Toast.makeText(this, "服务已启动，可切换到其他应用", Toast.LENGTH_LONG).show()
        } else {
            Log.w(TAG, "MediaProjection denied")
            Toast.makeText(this, "需要截屏权限才能使用", Toast.LENGTH_SHORT).show()
        }
    }

    // Step 2: 通知权限回调 → 继续请求 MediaProjection
    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d(TAG, "Notification permission granted: $granted")
        // 不管通知权限是否授予，都继续请求 MediaProjection
        requestMediaProjection()
    }

    // Step 1: 悬浮窗权限回调（通过 onResume 检测）
    override fun onResume() {
        super.onResume()
        if (pendingStart && Settings.canDrawOverlays(this)) {
            pendingStart = false
            Log.d(TAG, "Overlay permission granted, continuing...")
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
                    onStop = { stopServices() }
                )
            }
        }
    }

    /**
     * 启动流程：悬浮窗权限 → 通知权限 → MediaProjection → 启动服务
     */
    private fun startFlow() {
        Log.d(TAG, "startFlow: checking overlay permission")
        // Step 1: 悬浮窗权限
        if (!Settings.canDrawOverlays(this)) {
            pendingStart = true
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(this, "请授予悬浮窗权限，然后返回", Toast.LENGTH_LONG).show()
            return
        }
        requestNotificationPermission()
    }

    private fun requestNotificationPermission() {
        // Step 2: 通知权限 (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Log.d(TAG, "Requesting notification permission")
                notificationPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        requestMediaProjection()
    }

    private fun requestMediaProjection() {
        // Step 3: MediaProjection
        Log.d(TAG, "Requesting MediaProjection")
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(pm.createScreenCaptureIntent())
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
        Log.d(TAG, "Starting FloatingWindowService")
        val intent = Intent(this, FloatingWindowService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
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
