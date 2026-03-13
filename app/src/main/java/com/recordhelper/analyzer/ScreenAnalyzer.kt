package com.recordhelper.analyzer

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log

private const val TAG = "ScreenAnalyzer"

data class AnalyzeResult(
    val hasGreenIcon: Boolean = false,
    val hasGroupBuyText: Boolean = false,
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

    fun analyze(
        nodeInfos: List<NodeData>,
        bitmap: Bitmap?,
        ratioPercent: Int,
        minComments: Int
    ): AnalyzeResult {
        val allTexts = nodeInfos.flatMap { listOfNotNull(it.text, it.contentDesc) }
        val allDescs = nodeInfos.mapNotNull { it.contentDesc }

        // === 条件1: 绿标 - 像素检测 ===
        val hasGreenIcon = if (bitmap != null) detectGreenIcon(bitmap) else false

        // === 条件2: 团购文字 ===
        // 从你的截图看，团购视频底部有 "优惠团购"、"X款团购低至X折"、"已售XX万+"
        val hasGroupBuyText = allTexts.any { text ->
            text.contains("团购") || text.contains("已售")
        }

        // === 条件3: 北京地区 ===
        val isBeijing = allTexts.any { text ->
            text.contains("北京")
        }

        // === 提取互动数据 ===
        var likeCount = 0L
        var commentCount = 0L
        var favoriteCount = 0L
        var shareCount = 0L

        // 从所有文本和描述中提取
        for (info in nodeInfos) {
            // 检查 contentDescription
            info.contentDesc?.let { desc ->
                extractCounts(desc)?.let { (type, count) ->
                    when (type) {
                        "like" -> if (likeCount == 0L) likeCount = count
                        "comment" -> if (commentCount == 0L) commentCount = count
                        "favorite" -> if (favoriteCount == 0L) favoriteCount = count
                        "share" -> if (shareCount == 0L) shareCount = count
                    }
                }
            }
            // 检查 text
            info.text?.let { text ->
                extractCounts(text)?.let { (type, count) ->
                    when (type) {
                        "like" -> if (likeCount == 0L) likeCount = count
                        "comment" -> if (commentCount == 0L) commentCount = count
                        "favorite" -> if (favoriteCount == 0L) favoriteCount = count
                        "share" -> if (shareCount == 0L) shareCount = count
                    }
                }
            }
        }

        // 如果还没提取到，尝试从纯数字节点按顺序猜测
        // 抖音右侧栏从上到下: 点赞、评论、收藏、转发
        if (likeCount == 0L && commentCount == 0L) {
            val pureNumbers = allTexts.mapNotNull { text ->
                val trimmed = text.trim()
                if (Regex("^[\\d.]+[万wW]?$").matches(trimmed)) {
                    CountParser.parseLong(trimmed)
                } else null
            }.filter { it > 0 }

            if (pureNumbers.size >= 4) {
                likeCount = pureNumbers[0]
                commentCount = pureNumbers[1]
                favoriteCount = pureNumbers[2]
                shareCount = pureNumbers[3]
            }
        }

        val meetsComments = commentCount >= minComments
        val meetsRatio = checkRatio(likeCount, shareCount, ratioPercent)

        // 条件: 绿标 + 团购文字 + 北京 + 评论数 + 比例
        val meetsAll = hasGreenIcon && hasGroupBuyText && isBeijing && meetsComments && meetsRatio

        val debug = buildString {
            appendLine("绿标=$hasGreenIcon 团购=$hasGroupBuyText 北京=$isBeijing")
            appendLine("赞=$likeCount 评=$commentCount 藏=$favoriteCount 转=$shareCount")
            appendLine("评论>=$minComments:$meetsComments 比例>=${ratioPercent}%:$meetsRatio")
            appendLine("结果=$meetsAll")
        }
        Log.d(TAG, debug)

        return AnalyzeResult(
            hasGreenIcon = hasGreenIcon,
            hasGroupBuyText = hasGroupBuyText,
            likeCount = likeCount,
            commentCount = commentCount,
            favoriteCount = favoriteCount,
            shareCount = shareCount,
            isBeijing = isBeijing,
            meetsAllConditions = meetsAll,
            debugInfo = debug
        )
    }

    /** 从文本中提取互动数据类型和数量 */
    private fun extractCounts(text: String): Pair<String, Long>? {
        val lower = text.lowercase()
        val num = CountParser.parseLong(text)
        if (num <= 0) return null

        return when {
            (lower.contains("赞") || lower.contains("喜欢") || lower.contains("like"))
                && !lower.contains("评") && !lower.contains("收") -> "like" to num
            lower.contains("评论") || lower.contains("comment") -> "comment" to num
            lower.contains("收藏") || lower.contains("favorite") -> "favorite" to num
            (lower.contains("转发") || lower.contains("分享") || lower.contains("share"))
                && !lower.contains("收") -> "share" to num
            else -> null
        }
    }

    /** 检测绿标 - 屏幕左下区域的绿色定位图标 */
    private fun detectGreenIcon(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        // 扫描范围：左侧0~15%, 高度60%~90%（绿标在左下角）
        val scanXEnd = (w * 0.15).toInt()
        val scanYStart = (h * 0.60).toInt()
        val scanYEnd = (h * 0.90).toInt()
        var greenCount = 0

        for (x in 0 until scanXEnd step 2) {
            for (y in scanYStart until scanYEnd step 2) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                if (g > 140 && g > r + 30 && g > b + 30) {
                    greenCount++
                }
            }
        }
        Log.d(TAG, "Green pixels: $greenCount (threshold: 15)")
        return greenCount > 15
    }

    private fun checkRatio(like: Long, share: Long, ratioPercent: Int): Boolean {
        if (like == 0L || share == 0L) return false
        val smaller = minOf(like, share).toDouble()
        val larger = maxOf(like, share).toDouble()
        return (smaller / larger) >= ratioPercent / 100.0
    }
}
