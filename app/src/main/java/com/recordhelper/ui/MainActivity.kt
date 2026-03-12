package com.recordhelper.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.recordhelper.data.AppSettings
import com.recordhelper.service.DouyinAutoService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { MainScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    var ratioPercent by remember { mutableIntStateOf(AppSettings.getRatioPercent(context)) }
    var minComments by remember { mutableIntStateOf(AppSettings.getMinComments(context)) }
    var isRunning by remember { mutableStateOf(DouyinAutoService.isWorking) }
    var ratioExpanded by remember { mutableStateOf(false) }
    val ratioOptions = listOf(20, 30, 40, 50)

    Scaffold(
        topBar = { TopAppBar(title = { Text("抖音团购记录助手") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val serviceOk = DouyinAutoService.instance != null
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(if (serviceOk) "✅ 无障碍服务已连接" else "❌ 无障碍服务未开启",
                        style = MaterialTheme.typography.titleMedium)
                    if (!serviceOk) {
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            Toast.makeText(context, "请找到「记录助手」并开启", Toast.LENGTH_LONG).show()
                        }) { Text("去开启无障碍服务") }
                    }
                }
            }

            // 筛选条件
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("筛选条件", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("点赞/转发比例 ≥")
                    ExposedDropdownMenuBox(expanded = ratioExpanded, onExpandedChange = { ratioExpanded = it }) {
                        OutlinedTextField(
                            value = "${ratioPercent}%", onValueChange = {}, readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(ratioExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = ratioExpanded, onDismissRequest = { ratioExpanded = false }) {
                            ratioOptions.forEach { opt ->
                                DropdownMenuItem(text = { Text("${opt}%") }, onClick = {
                                    ratioPercent = opt; AppSettings.setRatioPercent(context, opt); ratioExpanded = false
                                })
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("最低评论数: $minComments")
                    Slider(
                        value = minComments.toFloat(),
                        onValueChange = { minComments = it.toInt(); AppSettings.setMinComments(context, minComments) },
                        valueRange = 5f..100f, steps = 18
                    )
                    Text("地区: 北京（固定）", style = MaterialTheme.typography.bodySmall)
                    Text("需要: 团购标识(已售/团购) + 北京地区 + 评论数 + 比例", style = MaterialTheme.typography.bodySmall)
                }
            }

            // 启动按钮
            Button(
                onClick = {
                    val svc = DouyinAutoService.instance
                    if (svc == null) {
                        Toast.makeText(context, "请先开启无障碍服务", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (isRunning) { svc.stopAutoScroll(); isRunning = false }
                    else { svc.startAutoScroll(); isRunning = true }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(if (isRunning) "⏹ 停止" else "▶ 开始自动刷抖音",
                    style = MaterialTheme.typography.titleMedium)
            }

            // 统计 + 调试信息
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${DouyinAutoService.savedCount}", style = MaterialTheme.typography.headlineMedium)
                            Text("已保存", style = MaterialTheme.typography.bodySmall)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${DouyinAutoService.skippedCount}", style = MaterialTheme.typography.headlineMedium)
                            Text("已跳过", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (DouyinAutoService.lastDebugInfo.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("最近分析:", style = MaterialTheme.typography.labelSmall)
                        Text(DouyinAutoService.lastDebugInfo, fontSize = 10.sp)
                    }
                }
            }

            Text("截图保存在: 相册 > RecordHelper", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
