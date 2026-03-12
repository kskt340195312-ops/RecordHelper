package com.recordhelper.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.recordhelper.data.AppSettings
import com.recordhelper.service.DouyinAutoService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MainScreen()
            }
        }
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 无障碍服务状态
            val serviceConnected = DouyinAutoService.instance != null
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (serviceConnected) "✅ 无障碍服务已连接" else "❌ 无障碍服务未开启",
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (!serviceConnected) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            Toast.makeText(context, "请找到「记录助手」并开启", Toast.LENGTH_LONG).show()
                        }) {
                            Text("去开启无障碍服务")
                        }
                    }
                }
            }

            // 筛选条件设置
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("筛选条件", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(12.dp))

                    // 比例条件
                    Text("点赞/转发比例 ≥")
                    ExposedDropdownMenuBox(
                        expanded = ratioExpanded,
                        onExpandedChange = { ratioExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = "${ratioPercent}%",
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = ratioExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = ratioExpanded,
                            onDismissRequest = { ratioExpanded = false }
                        ) {
                            ratioOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text("${option}%") },
                                    onClick = {
                                        ratioPercent = option
                                        AppSettings.setRatioPercent(context, option)
                                        ratioExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text("最低评论数: $minComments")
                    Slider(
                        value = minComments.toFloat(),
                        onValueChange = {
                            minComments = it.toInt()
                            AppSettings.setMinComments(context, minComments)
                        },
                        valueRange = 5f..100f,
                        steps = 18
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Text("地区: 北京（固定）", style = MaterialTheme.typography.bodySmall)
                    Text("团购标识: 自动检测", style = MaterialTheme.typography.bodySmall)
                }
            }

            // 启动/停止按钮
            Button(
                onClick = {
                    val service = DouyinAutoService.instance
                    if (service == null) {
                        Toast.makeText(context, "请先开启无障碍服务", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (isRunning) {
                        service.stopAutoScroll()
                        isRunning = false
                    } else {
                        service.startAutoScroll()
                        isRunning = true
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    if (isRunning) "⏹ 停止自动刷" else "▶ 开始自动刷抖音",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            // 统计
            if (isRunning || DouyinAutoService.savedCount > 0) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${DouyinAutoService.savedCount}", style = MaterialTheme.typography.headlineMedium)
                            Text("已保存", style = MaterialTheme.typography.bodySmall)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${DouyinAutoService.skippedCount}", style = MaterialTheme.typography.headlineMedium)
                            Text("已跳过", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))
            Text(
                "截图保存在: 相册 > RecordHelper 文件夹",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
