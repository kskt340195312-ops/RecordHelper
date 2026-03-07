package com.recordhelper.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.recordhelper.data.RecordRepository
import com.recordhelper.data.VideoRecordEntity
import com.recordhelper.export.ExcelExporter
import com.recordhelper.service.CaptureForegroundService
import com.recordhelper.service.FloatingWindowService

/**
 * 主界面
 * 用 Compose 展示记录列表、启停服务、导出 Excel
 * 权限申请流程完整（悬浮窗 → 通知 → MediaProjection）
 */
class MainActivity : ComponentActivity() {

    private lateinit var repository: RecordRepository

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            CaptureForegroundService.setProjectionData(result.resultCode, result.data!!)
            startCaptureService()
            startFloatingService()
        } else {
            Toast.makeText(this, "需要截屏权限才能使用", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = RecordRepository(this)

        setContent {
            MaterialTheme {
                MainScreen(repository = repository, onStart = { requestPermissions() }, onStop = { stopServices() })
            }
        }
    }

    private fun requestPermissions() {
        // 1. 悬浮窗权限
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
            Toast.makeText(this, "请授予悬浮窗权限后重试", Toast.LENGTH_LONG).show()
            return
        }

        // 2. 通知权限 (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
        }

        // 3. MediaProjection 权限
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startCaptureService() {
        val intent = Intent(this, CaptureForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun startFloatingService() {
        startService(Intent(this, FloatingWindowService::class.java))
    }

    private fun stopServices() {
        stopService(Intent(this, FloatingWindowService::class.java))
        stopService(Intent(this, CaptureForegroundService::class.java))
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
        topBar = {
            TopAppBar(title = { Text("记录助手") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // 控制按钮行
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

            // 记录列表
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(records) { record ->
                    RecordCard(record)
                }
            }
        }
    }
}

@Composable
fun RecordCard(record: VideoRecordEntity) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
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
