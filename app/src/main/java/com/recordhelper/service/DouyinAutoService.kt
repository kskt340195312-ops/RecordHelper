package com.recordhelper.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.recordhelper.analyzer.NodeData
import com.recordhelper.analyzer.ScreenAnalyzer
import com.recordhelper.data.AppSettings
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

private const val TAG = "DouyinAutoService"

class DouyinAutoService : AccessibilityService() {

    companion object {
        var instance: DouyinAutoService? = null
            private set
        var isWorking = false
            private set
        var savedCount = 0
            private set
        var skippedCount = 0
            private set
        var lastDebugInfo = ""
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private val analyzer = ScreenAnalyzer()
    private var isProcessing = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")
        Toast.makeText(this, "记录助手无障碍服务已连接", Toast.LENGTH_SHORT).show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        instance = null
        isWorking = false
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    fun startAutoScroll() {
        if (isWorking) return
        isWorking = true
        savedCount = 0
        skippedCount = 0
        isProcessing = false
        lastDebugInfo = ""
        Log.d(TAG, "Starting auto scroll")
        Toast.makeText(this, "5秒后开始，请切换到抖音", Toast.LENGTH_LONG).show()
        handler.postDelayed({ processCurrentScreen() }, 5000)
    }

    fun stopAutoScroll() {
        isWorking = false
        isProcessing = false
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Stopped. Saved: $savedCount, Skipped: $skippedCount")
        Toast.makeText(this, "已停止。保存${savedCount}张，跳过${skippedCount}个", Toast.LENGTH_LONG).show()
    }

    private fun processCurrentScreen() {
        if (!isWorking || isProcessing) return
        isProcessing = true

        try {
            // 1. 收集屏幕节点数据（结构化）
            val nodeInfos = collectScreenNodes()
            Log.d(TAG, "Collected ${nodeInfos.size} nodes")

            if (nodeInfos.isEmpty()) {
                isProcessing = false
                if (isWorking) handler.postDelayed({ processCurrentScreen() }, 3000)
                return
            }

            // 打印所有节点用于调试
            nodeInfos.forEachIndexed { i, n ->
                Log.d(TAG, "Node[$i] text=${n.text}, desc=${n.contentDesc}, class=${n.className}")
            }

            // 2. 分析
            val ratioPercent = AppSettings.getRatioPercent(this)
            val minComments = AppSettings.getMinComments(this)
            val result = analyzer.analyzeFromNodes(nodeInfos, ratioPercent, minComments)
            lastDebugInfo = result.debugInfo

            if (result.meetsAllConditions) {
                Log.d(TAG, "✅ MATCH! Taking screenshot")
                handler.post {
                    Toast.makeText(this, "✅ 发现符合条件的视频!", Toast.LENGTH_SHORT).show()
                }
                takeScreenshotAndSave {
                    savedCount++
                    swipeToNext {
                        isProcessing = false
                        if (isWorking) handler.postDelayed({ processCurrentScreen() }, 3000)
                    }
                }
            } else {
                skippedCount++
                Log.d(TAG, "❌ Skip #$skippedCount: ${result.debugInfo.replace("\n", " | ")}")
                swipeToNext {
                    isProcessing = false
                    if (isWorking) handler.postDelayed({ processCurrentScreen() }, 2000)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing screen", e)
            isProcessing = false
            if (isWorking) handler.postDelayed({ processCurrentScreen() }, 3000)
        }
    }

    /** 收集屏幕上所有节点的结构化数据 */
    private fun collectScreenNodes(): List<NodeData> {
        val nodes = mutableListOf<NodeData>()
        val rootNode = rootInActiveWindow ?: return nodes
        collectNodesRecursive(rootNode, nodes)
        rootNode.recycle()
        return nodes
    }

    private fun collectNodesRecursive(node: AccessibilityNodeInfo, nodes: MutableList<NodeData>) {
        val text = node.text?.toString()?.takeIf { it.isNotBlank() }
        val desc = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        val className = node.className?.toString()

        if (text != null || desc != null) {
            nodes.add(NodeData(text = text, contentDesc = desc, className = className))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNodesRecursive(child, nodes)
            child.recycle()
        }
    }

    private fun swipeToNext(onDone: () -> Unit) {
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels

        val path = Path().apply {
            moveTo(w / 2f, h * 0.75f)
            lineTo(w / 2f, h * 0.25f)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                handler.postDelayed(onDone, 1500)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                handler.postDelayed(onDone, 1000)
            }
        }, handler)
    }

    private fun takeScreenshotAndSave(onDone: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val bitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        if (bitmap != null) {
                            val soft = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                            bitmap.recycle()
                            result.hardwareBuffer.close()
                            saveBitmapToGallery(soft)
                            soft.recycle()
                        }
                        onDone()
                    }
                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "Screenshot failed: $errorCode")
                        onDone()
                    }
                }
            )
        } else {
            Log.w(TAG, "Screenshot not supported below Android 11")
            onDone()
        }
    }

    private fun saveBitmapToGallery(bitmap: Bitmap) {
        try {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.CHINA).format(Date())
            val filename = "douyin_$ts.jpg"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RecordHelper")
                }
                contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.let { uri ->
                    contentResolver.openOutputStream(uri)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    }
                }
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "RecordHelper")
                dir.mkdirs()
                FileOutputStream(File(dir, filename)).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
            }
            Log.d(TAG, "Saved: $filename")
        } catch (e: Exception) {
            Log.e(TAG, "Save failed", e)
        }
    }
}
