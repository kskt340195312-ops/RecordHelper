package com.recordhelper.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
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
    override fun onInterrupt() { Log.d(TAG, "Service interrupted") }

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
            val nodeInfos = collectScreenNodes()
            Log.d(TAG, "Collected ${nodeInfos.size} nodes")

            if (nodeInfos.isEmpty()) {
                isProcessing = false
                if (isWorking) handler.postDelayed({ processCurrentScreen() }, 1000)
                return
            }

            // 保存前5次的节点数据到文件，方便调试
            if (skippedCount + savedCount < 5) {
                saveDebugNodes(nodeInfos)
            }

            captureForAnalysis { bitmap ->
                val ratioPercent = AppSettings.getRatioPercent(this)
                val minComments = AppSettings.getMinComments(this)
                val result = analyzer.analyze(nodeInfos, bitmap, ratioPercent, minComments)
                lastDebugInfo = result.debugInfo
                bitmap?.recycle()

                if (result.meetsAllConditions) {
                    Log.d(TAG, "✅ MATCH! Starting profile flow for publish time")
                    handler.post {
                        Toast.makeText(this, "✅ 符合条件! 正在获取发布时间...", Toast.LENGTH_SHORT).show()
                    }
                    // 进主页 → 点视频 → 截图 → 返回
                    navigateToProfileAndScreenshot {
                        savedCount++
                        isProcessing = false
                        if (isWorking) handler.postDelayed({ processCurrentScreen() }, 1500)
                    }
                } else {
                    skippedCount++
                    Log.d(TAG, "❌ Skip #$skippedCount: ${result.debugInfo.trim()}")
                    handler.post {
                        Toast.makeText(this, "跳过#$skippedCount: ${result.debugInfo.lines().first()}", Toast.LENGTH_SHORT).show()
                    }
                    swipeToNext {
                        isProcessing = false
                        if (isWorking) handler.postDelayed({ processCurrentScreen() }, 800)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing screen", e)
            isProcessing = false
            if (isWorking) handler.postDelayed({ processCurrentScreen() }, 3000)
        }
    }

    /**
     * 进入作者主页 → 点击第一个视频 → 等待发布时间显示 → 截图 → 返回feed
     *
     * 流程：
     * 1. 找到作者头像节点并点击 → 进入主页
     * 2. 等待主页加载，找到视频列表中的第一个视频并点击
     * 3. 等待视频加载（此时会显示发布时间）
     * 4. 截图保存
     * 5. 按返回键两次回到feed
     */
    private fun navigateToProfileAndScreenshot(onDone: () -> Unit) {
        // Step 1: 点击作者头像
        val avatarClicked = clickAuthorAvatar()
        if (!avatarClicked) {
            Log.w(TAG, "Could not find author avatar, taking screenshot directly")
            takeScreenshotAndSave {
                swipeToNext { onDone() }
            }
            return
        }

        // Step 2: 等待主页加载，然后点击第一个视频
        handler.postDelayed({
            if (!isWorking) { onDone(); return@postDelayed }

            val videoClicked = clickFirstVideoInProfile()
            if (!videoClicked) {
                Log.w(TAG, "Could not find video in profile, going back and taking screenshot")
                performGlobalAction(GLOBAL_ACTION_BACK)
                handler.postDelayed({
                    takeScreenshotAndSave {
                        swipeToNext { onDone() }
                    }
                }, 1500)
                return@postDelayed
            }

            // Step 3: 等待视频加载（发布时间会显示）
            handler.postDelayed({
                if (!isWorking) { onDone(); return@postDelayed }

                // Step 4: 截图
                Log.d(TAG, "Taking screenshot with publish time visible")
                handler.post {
                    Toast.makeText(this, "📸 截图中（含发布时间）", Toast.LENGTH_SHORT).show()
                }
                takeScreenshotAndSave {
                    // Step 5: 返回到feed — 按两次返回
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    handler.postDelayed({
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        handler.postDelayed({
                            // 等一下让feed稳定，然后滑到下一个
                            swipeToNext { onDone() }
                        }, 1500)
                    }, 1500)
                }
            }, 3000) // 等3秒让视频加载
        }, 2500) // 等2.5秒让主页加载
    }

    /**
     * 点击作者头像。
     * 抖音视频页面右侧有作者头像，通常是一个圆形ImageView。
     * 查找策略：
     * - 找 contentDescription 包含"头像"的节点
     * - 或者找右侧区域的 ImageView 节点（头像在右侧栏最上方）
     */
    private fun clickAuthorAvatar(): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            // 策略1: 找包含"头像"的节点
            val avatarNode = findNodeByDescContains(root, "头像")
            if (avatarNode != null) {
                val clicked = clickNode(avatarNode)
                avatarNode.recycle()
                if (clicked) {
                    Log.d(TAG, "Clicked avatar via desc '头像'")
                    return true
                }
            }

            // 策略2: 找包含"进入"+"主页"的节点
            val profileNode = findNodeByDescContains(root, "主页")
            if (profileNode != null) {
                val clicked = clickNode(profileNode)
                profileNode.recycle()
                if (clicked) {
                    Log.d(TAG, "Clicked avatar via desc '主页'")
                    return true
                }
            }

            // 策略3: 在右侧上方区域找可点击的ImageView
            val dm = resources.displayMetrics
            val screenW = dm.widthPixels
            val screenH = dm.heightPixels
            val rightAreaNode = findClickableImageInArea(
                root,
                left = (screenW * 0.80).toInt(),
                top = (screenH * 0.20).toInt(),
                right = screenW,
                bottom = (screenH * 0.50).toInt()
            )
            if (rightAreaNode != null) {
                val clicked = clickNode(rightAreaNode)
                rightAreaNode.recycle()
                if (clicked) {
                    Log.d(TAG, "Clicked avatar via position (right side ImageView)")
                    return true
                }
            }

            return false
        } finally {
            root.recycle()
        }
    }

    /**
     * 在作者主页中点击第一个视频。
     * 主页通常有一个视频网格，点击第一个即可。
     * 查找策略：
     * - 找 RecyclerView/ListView 中的第一个可点击项
     * - 或者找屏幕中间区域的可点击 ImageView
     */
    private fun clickFirstVideoInProfile(): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            // 策略1: 找包含"作品"或"视频"标签附近的可点击元素
            // 主页视频网格通常在屏幕中下部
            val dm = resources.displayMetrics
            val screenW = dm.widthPixels
            val screenH = dm.heightPixels

            // 找屏幕中间偏左上的可点击元素（第一个视频通常在这个位置）
            val videoNode = findClickableInArea(
                root,
                left = 0,
                top = (screenH * 0.40).toInt(),
                right = (screenW * 0.55).toInt(),
                bottom = (screenH * 0.75).toInt()
            )
            if (videoNode != null) {
                val clicked = clickNode(videoNode)
                videoNode.recycle()
                if (clicked) {
                    Log.d(TAG, "Clicked first video in profile")
                    return true
                }
            }

            // 策略2: 直接点击屏幕中间偏左的位置（视频网格第一个位置）
            val tapX = screenW * 0.25f
            val tapY = screenH * 0.55f
            tapAt(tapX, tapY) {
                Log.d(TAG, "Tapped at ($tapX, $tapY) for first video")
            }
            return true
        } finally {
            root.recycle()
        }
    }

    // === 辅助方法：节点查找和点击 ===

    private fun findNodeByDescContains(root: AccessibilityNodeInfo, keyword: String): AccessibilityNodeInfo? {
        val desc = root.contentDescription?.toString() ?: ""
        if (desc.contains(keyword)) return AccessibilityNodeInfo.obtain(root)

        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNodeByDescContains(child, keyword)
            if (found != null) {
                child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }

    private fun findClickableImageInArea(
        root: AccessibilityNodeInfo, left: Int, top: Int, right: Int, bottom: Int
    ): AccessibilityNodeInfo? {
        val className = root.className?.toString() ?: ""
        val rect = Rect()
        root.getBoundsInScreen(rect)

        if ((className.contains("ImageView") || className.contains("Image")) &&
            rect.left >= left && rect.top >= top && rect.right <= right && rect.bottom <= bottom &&
            (root.isClickable || root.parent?.isClickable == true)
        ) {
            return AccessibilityNodeInfo.obtain(root)
        }

        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findClickableImageInArea(child, left, top, right, bottom)
            if (found != null) {
                child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }

    private fun findClickableInArea(
        root: AccessibilityNodeInfo, left: Int, top: Int, right: Int, bottom: Int
    ): AccessibilityNodeInfo? {
        val rect = Rect()
        root.getBoundsInScreen(rect)

        if (root.isClickable &&
            rect.centerX() in left..right &&
            rect.centerY() in top..bottom &&
            rect.width() > 50 && rect.height() > 50
        ) {
            return AccessibilityNodeInfo.obtain(root)
        }

        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findClickableInArea(child, left, top, right, bottom)
            if (found != null) {
                child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        // 先尝试直接点击
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        // 向上找可点击的父节点
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable) {
                val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                parent.recycle()
                return result
            }
            val grandParent = parent.parent
            parent.recycle()
            parent = grandParent
        }
        // 都不行就用手势点击节点中心
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) {
            tapAt(rect.centerX().toFloat(), rect.centerY().toFloat()) {}
            return true
        }
        return false
    }

    private fun tapAt(x: Float, y: Float, onDone: () -> Unit) {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { onDone() }
            override fun onCancelled(gestureDescription: GestureDescription?) { onDone() }
        }, handler)
    }

    // === 原有方法 ===

    /** 保存节点数据到文件用于调试 */
    private fun saveDebugNodes(nodes: List<NodeData>) {
        try {
            val ts = SimpleDateFormat("HHmmss", Locale.CHINA).format(Date())
            val sb = StringBuilder()
            sb.appendLine("=== Nodes at $ts ===")
            nodes.forEachIndexed { i, n ->
                sb.appendLine("[$i] class=${n.className}")
                if (n.text != null) sb.appendLine("    text: ${n.text}")
                if (n.contentDesc != null) sb.appendLine("    desc: ${n.contentDesc}")
            }
            val filename = "debug_nodes_$ts.txt"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Files.FileColumns.DISPLAY_NAME, filename)
                    put(MediaStore.Files.FileColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.Files.FileColumns.RELATIVE_PATH, "Documents/RecordHelper")
                }
                contentResolver.insert(MediaStore.Files.getContentUri("external"), values)?.let { uri ->
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(sb.toString().toByteArray())
                    }
                }
            }
            Log.d(TAG, "Debug nodes saved: $filename")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save debug nodes", e)
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

    /** 截图用于像素分析（不保存） */
    private fun captureForAnalysis(callback: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val bitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        val soft = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                        bitmap?.recycle()
                        result.hardwareBuffer.close()
                        callback(soft)
                    }
                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "Analysis screenshot failed: $errorCode")
                        callback(null)
                    }
                }
            )
        } else {
            callback(null)
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
            .addStroke(GestureDescription.StrokeDescription(path, 0, 200))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                handler.postDelayed(onDone, 800)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                handler.postDelayed(onDone, 500)
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
