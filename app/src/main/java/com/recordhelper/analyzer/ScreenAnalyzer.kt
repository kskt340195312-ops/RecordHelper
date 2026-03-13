package com.recordhelper.analyzer

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log

private const val TAG = "ScreenAnalyzer"

data class AnalyzeResult(
    val hasGreenIcon: Boolean = false,
    val hasOrangeDot: Boolean = false,
    val likeCount: Long = 0,
    val commentCount: Long = 0,
    val favoriteCount: Long = 0,
    val shareCount: Long = 0,
    val isBeijing: Boolean = false,
    val meetsAllConditions: Boolean = false,
    val debugInfo: String = ""
)

data class NodeData(
    val text: String?,
    val contentDesc: String?,
    val className: String? = null
)

class ScreenAnalyzer {

    /**
     * 完整分析：文本节点 + 截图像素
     * @param nodeInfos 无障碍服务获取的节点数据
     * @param bitmap 截图（用于检测绿标和小圆点）
     * @param ratioPercent 比例条件
     * @param minComments 最低评论数
     */
    fun analyze(
        nodeInfos: List<NodeData>,
        bitmap: Bitmap?,
        ratioPercent: Int,
        minComments: Int
    ): AnalyzeResult {
        val allTexts = nodeInfos.flatMap { listOfNotNull(it.text, it.contentDesc) }
        val allDescs = nodeInfos.mapNotNull { it.contentDesc }

        // === 条件1: 绿标（绿色定位图标）===
        // 通过截图像素检测，绿标在屏幕左下区域
        val hasGreenIcon = if (bitmap != null) detectGreenIcon(bitmap) else false

        // === 条件2: 小圆点（团购链接标识）===
        // 橙色/红色小圆点，在右侧收藏星标附近
        val hasOrangeDot = if (bitmap != null) detectOrangeDot(bitmap) else false

        // === 条件3: 北京地区 ===
        val isBeijing = allTexts.any {
            it.contains("北京市") || it.contains("北京·") || it.contains("北京 ")
        }

        // === 提取互动数据 ===
        var likeCount = 0L
        var commentCount = 0L
        var favoriteCount = 0L
        var shareCount = 0L

        for (desc in allDescs) {
            val lower = desc.lowercase()
            when {
                (lower.contains("赞") || lower.contains("喜欢") || lower.contains("like"))
                    && !lower.contains("评") && !lower.contains("收") -> {
                    val n = CountParser.parseLong(desc)
                    if (n > 0) likeCount = n
                }
                lower.contains("评论") || lower.contains("comment") -> {
                    val n = CountParser.parseLong(desc)
                    if (n > 0) commentCount = n
                }
                lower.contains("收藏") || lower.contains("favorite") -> {
                    val n = CountParser.parseLong(desc)
                    if (n > 0) favoriteCount = n
                }
                (lower.contains("转发") || lower.contains("分享") || lower.contains("share"))
                    && !lower.contains("收") -> {
                    val n = CountParser.parseLong(desc)
                    if (n > 0) shareCount = n
                }
            }
        }
        // 也从 text 中提取
        for (info in nodeInfos) {
            val text = info.text ?: continue
            val lower = text.lowercase()
            when {
                (lower.contains("赞") || lower.contains("喜欢"))
                    && !lower.contains("评") && !lower.contains("收") && likeCount == 0L -> {
                    val n = CountParser.parseLong(text); if (n > 0) likeCount = n
                }
                lower.contains("评论") && commentCount == 0L -> {
                    val n = CountParser.parseLong(text); if (n > 0) commentCount = n
                }
                lower.contains("收藏") && favoriteCount == 0L -> {
                    val n = CountParser.parseLong(text); if (n > 0) favoriteCount = n
                }
                (lower.contains("转发") || lower.contains("分享")) && shareCount == 0L -> {
                    val n = CountParser.parseLong(text); if (n > 0) shareCount = n
                }
            }
        }

        // === 条件4: 评论数 ===
        val meetsComments = commentCount >= minComments

        // === 条件5: 比例条件 ===
        val meetsRatio = checkRatio(likeCount, shareCount, ratioPercent)

        // 全部5个条件必须同时满足
        val meetsAll = hasGreenIcon && hasOrangeDot && isBeijing && meetsComments && meetsRatio

        val debug = buildString {
            appendLine("绿标=$hasGreenIcon, 小圆点=$hasOrangeDot, 北京=$isBeijing")
            appendLine("赞=$likeCount, 评=$commentCount, 藏=$favoriteCount, 转=$shareCount")
            appendLine("评论>=$minComments: $meetsComments")
            appendLine("比例>=${ratioPercent}%: $meetsRatio")
            appendLine("总判定: $meetsAll")
        }
        Log.d(TAG, debug)

        return AnalyzeResult(
            hasGreenIcon = hasGreenIcon,
            hasOrangeDot = hasOrangeDot,
            likeCount = likeCount,
            commentCount = commentCount,
            favoriteCount = favoriteCount,
            shareCount = shareCount,
            isBeijing = isBeijing,
            meetsAllConditions = meetsAll,
            debugInfo = debug
        )
    }

    /**
     * 检测绿标（绿色定位图标）
     * 位置：屏幕左下区域（x: 0~40%, y: 65%~85%）
     * 颜色：绿色 (G > 150, G > R+50, G > B+50)
     */
    private fun detectGreenIcon(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        val scanX = (w * 0.35).toInt()
        val scanYStart = (h * 0.65).toInt()
        val scanYEnd = (h * 0.85).toInt()
        var greenCount = 0

        for (x in 0 until scanX step 3) {
            for (y in scanYStart until scanYEnd step 3) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                // 绿色：G通道高，明显大于R和B
                if (g > 150 && g > r + 40 && g > b + 40) {
                    greenCount++
                }
            }
        }
        Log.d(TAG, "Green pixels: $greenCount")
        // 绿标图标大约有30+个绿色像素（采样后）
        return greenCount > 20
    }

    /**
     * 检测小圆点（团购链接标识）
     * 位置：屏幕右侧互动栏区域（x: 80%~100%, y: 40%~75%）
     * 颜色：橙色/红色小圆点 (R > 200, G: 50~150, B < 80)
     * 小圆点很小，只需要检测到少量像素即可
     */
    private fun detectOrangeDot(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        val scanXStart = (w * 0.80).toInt()
        val scanYStart = (h * 0.40).toInt()
        val scanYEnd = (h * 0.75).toInt()
        var orangeCount = 0

        for (x in scanXStart until w step 2) {
            for (y in scanYStart until scanYEnd step 2) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                // 橙色/红色小圆点
                if (r > 200 && g in 40..160 && b < 100) {
                    orangeCount++
                }
            }
        }
        Log.d(TAG, "Orange dot pixels: $orangeCount")
        // 小圆点很小，5个以上采样像素就算检测到
        return orangeCount in 5..500
    }

    private fun checkRatio(like: Long, share: Long, ratioPercent: Int): Boolean {
        if (like == 0L || share == 0L) return false
        val smaller = minOf(like, share).toDouble()
        val larger = maxOf(like, share).toDouble()
        return (smaller / larger) >= ratioPercent / 100.0
    }
}
