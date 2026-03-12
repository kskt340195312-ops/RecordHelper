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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 我们不依赖事件驱动，而是用定时循环主动分析
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        instance = null
        isWorking = false
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** 从 MainActivity 调用，开始自动刷抖音流程 */
    fun startAutoScroll() {
        if (isWorking) return
        isWorking = true
        savedCount = 0
        skippedCount = 0
        isProcessing = false
        Log.d(TAG, "Starting auto scroll")
        Toast.makeText(this, "开始自动刷抖音，请切换到抖音", Toast.LENGTH_LONG).show()
        // 延迟5秒开始，给用户时间切换到抖音
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
            // 1. 读取屏幕上所有文本节点
            val nodeTexts = collectScreenTexts()
            Log.d(TAG, "Collected ${nodeTexts.size} text nodes")

            if (nodeTexts.isEmpty()) {
                // 可能不在抖音，等待后重试
                isProcessing = false
                if (isWorking) handler.postDelayed({ processCurrentScreen() }, 3000)
                return
            }

            // 2. 分析是否符合条件
            val ratioPercent = AppSettings.getRatioPercent(this)
            val minComments = AppSettings.getMinComments(this)
            val result = analyzer.analyzeFromTexts(nodeTexts, ratioPercent, minComments)

            if (result.meetsAllConditions) {
                // 3. 符合条件 → 截屏保存
                Log.d(TAG, "✅ Meets conditions, taking screenshot")
                takeScreenshotAndSave {
                    savedCount++
                    handler.post {
                        Toast.makeText(this, "✅ 已保存第${savedCount}张截图", Toast.LENGTH_SHORT).show()
                    }
                    // 截图保存后，滑到下一个
                    swipeToNext {
                        isProcessing = false
                        if (isWorking) handler.postDelayed({ processCurrentScreen() }, 3000)
                    }
                }
            } else {
                // 4. 不符合 → 直接滑到下一个
                skippedCount++
                Log.d(TAG, "❌ Skip #$skippedCount: beijing=${result.isBeijing}, groupBuy=${result.hasGroupBuyDot}, comment=${result.commentCount}, ratio check")
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

    /** 收集屏幕上所有文本节点 */
    private fun collectScreenTexts(): List<String> {
        val texts = mutableListOf<String>()
        val rootNode = rootInActiveWindow ?: return texts
        collectTextsRecursive(rootNode, texts)
        rootNode.recycle()
        return texts
    }

    private fun collectTextsRecursive(node: AccessibilityNodeInfo, texts: MutableList<String>) {
        // 获取文本
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts.add(it) }
        // 获取 contentDescription（抖音的互动数据经常在这里）
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { texts.add(it) }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTextsRecursive(child, texts)
            child.recycle()
        }
    }

    /** 上滑切换到下一个视频 */
    private fun swipeToNext(onDone: () -> Unit) {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val path = Path().apply {
            moveTo(screenWidth / 2f, screenHeight * 0.75f)
            lineTo(screenWidth / 2f, screenHeight * 0.25f)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Swipe completed")
                // 等待页面加载
                handler.postDelayed(onDone, 1500)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Swipe cancelled")
                handler.postDelayed(onDone, 1000)
            }
        }, handler)
    }

    /** 截屏并保存到相册 */
    private fun takeScreenshotAndSave(onDone: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ 使用 AccessibilityService.takeScreenshot
            takeScreenshot(Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val bitmap = Bitmap.wrapHardwareBuffer(
                            result.hardwareBuffer, result.colorSpace
                        )
                        if (bitmap != null) {
                            val softBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                            bitmap.recycle()
                            result.hardwareBuffer.close()
                            saveBitmapToGallery(softBitmap)
                            softBitmap.recycle()
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
            // Android 10 及以下，无法通过无障碍服务截屏
            Log.w(TAG, "Screenshot not supported below Android 11")
            onDone()
        }
    }

    private fun saveBitmapToGallery(bitmap: Bitmap) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
            val filename = "douyin_${timestamp}.jpg"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 使用 MediaStore
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RecordHelper")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    contentResolver.openOutputStream(it)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    }
                }
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "RecordHelper"
                )
                dir.mkdirs()
                val file = File(dir, filename)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
            }
            Log.d(TAG, "Screenshot saved: $filename")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save screenshot", e)
        }
    }
}
