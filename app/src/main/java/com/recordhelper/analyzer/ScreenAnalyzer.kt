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

        // === 条件1: 绿标 - 像素检测 ===
        val hasGreenIcon = if (bitmap != null) detectGreenIcon(bitmap) else false

        // === 条件2: 团购文字 ===
        val hasGroupBuyText = allTexts.any { text ->
            text.contains("团购") || text.contains("已售")
        }

        // === 条件3: 北京地区 ===
        val isBeijing = allTexts.any { text ->
            text.contains("北京")
        }

        // === 提取互动数据 ===
        val counts = extractAllCounts(nodeInfos)
        val likeCount = counts.like
        val commentCount = counts.comment
        val favoriteCount = counts.favorite
        val shareCount = counts.share

        val meetsComments = commentCount >= minComments
        val meetsRatio = checkRatio(likeCount, shareCount, ratioPercent)

        // 条件: 绿标 + 团购文字 + 北京 + 评论数 + 比例
        val meetsAll = hasGreenIcon && hasGroupBuyText && isBeijing && meetsComments && meetsRatio

        val debug = buildString {
            appendLine("绿标=$hasGreenIcon 团购=$hasGroupBuyText 北京=$isBeijing")
            appendLine("赞=$likeCount 评=$commentCount 藏=$favoriteCount 转=$shareCount")
            appendLine("评论>=$minComments:$meetsComments 比例>=${ratioPercent}%:$meetsRatio")
            appendLine("提取方式=${counts.method}")
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

    private data class Counts(
        val like: Long = 0,
        val comment: Long = 0,
        val favorite: Long = 0,
        val share: Long = 0,
        val method: String = "none"
    )

    /**
     * 从节点中提取互动数据。
     * 抖音的无障碍节点格式多种多样，常见的有：
     * - contentDescription: "点赞，3785" / "评论，128" / "收藏，56" / "转发，89"
     * - contentDescription: "3785赞" / "128评论"
     * - text: "3785" (纯数字，无关键词)
     *
     * 策略：
     * 1. 优先用带关键词的节点精确匹配
     * 2. 不再使用纯数字猜测（这是之前出错的根源）
     * 3. 如果无法确定评论数，返回0（宁可漏截不乱截）
     */
    private fun extractAllCounts(nodeInfos: List<NodeData>): Counts {
        var like = 0L
        var comment = 0L
        var favorite = 0L
        var share = 0L
        val methods = mutableListOf<String>()

        for (info in nodeInfos) {
            val texts = listOfNotNull(info.text, info.contentDesc)
            for (t in texts) {
                val parsed = parseCountFromText(t) ?: continue
                when (parsed.first) {
                    "like" -> if (like == 0L) { like = parsed.second; methods.add("赞:$t") }
                    "comment" -> if (comment == 0L) { comment = parsed.second; methods.add("评:$t") }
                    "favorite" -> if (favorite == 0L) { favorite = parsed.second; methods.add("藏:$t") }
                    "share" -> if (share == 0L) { share = parsed.second; methods.add("转:$t") }
                }
            }
        }

        return Counts(like, comment, favorite, share, methods.joinToString("; ").ifEmpty { "none" })
    }

    /**
     * 从单条文本中解析互动类型和数量。
     * 支持格式：
     * - "点赞，3785" / "点赞,3785" / "点赞 3785"
     * - "评论，128" / "评论,128"
     * - "收藏，56" / "收藏,56"
     * - "转发，89" / "分享，89"
     * - "3785赞" / "128评论" / "56收藏" / "89转发"
     * - "喜欢，3785"
     * - "已点赞，3785"
     * - 不处理纯数字（避免误判）
     */
    private fun parseCountFromText(text: String): Pair<String, Long>? {
        val t = text.trim()
        if (t.isEmpty()) return null

        // 格式1: "关键词[分隔符]数字" — 如 "点赞，3785" "评论,128" "收藏 1.2万"
        val prefixPattern = Regex("(点赞|已点赞|赞|喜欢|like|评论|comment|收藏|favorite|转发|分享|share)[，,\\s:：]+(.+)", RegexOption.IGNORE_CASE)
        prefixPattern.find(t)?.let { m ->
            val keyword = m.groupValues[1].lowercase()
            val numStr = m.groupValues[2].trim()
            val num = CountParser.parseLong(numStr)
            if (num > 0) {
                val type = classifyKeyword(keyword) ?: return null
                return type to num
            }
        }

        // 格式2: "数字+关键词" — 如 "3785赞" "128评论"
        val suffixPattern = Regex("([\\d.]+[万wW]?)\\s*(点赞|赞|喜欢|评论|收藏|转发|分享)", RegexOption.IGNORE_CASE)
        suffixPattern.find(t)?.let { m ->
            val numStr = m.groupValues[1]
            val keyword = m.groupValues[2].lowercase()
            val num = CountParser.parseLong(numStr)
            if (num > 0) {
                val type = classifyKeyword(keyword) ?: return null
                return type to num
            }
        }

        // 不处理纯数字 — 这是之前出错的根源
        return null
    }

    private fun classifyKeyword(keyword: String): String? {
        return when {
            keyword.contains("赞") || keyword.contains("喜欢") || keyword.contains("like") -> "like"
            keyword.contains("评论") || keyword.contains("comment") -> "comment"
            keyword.contains("收藏") || keyword.contains("favorite") -> "favorite"
            keyword.contains("转发") || keyword.contains("分享") || keyword.contains("share") -> "share"
            else -> null
        }
    }

    /** 检测绿标 - 屏幕左下区域的绿色定位图标 */
    private fun detectGreenIcon(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
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
