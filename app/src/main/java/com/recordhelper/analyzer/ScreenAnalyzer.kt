package com.recordhelper.analyzer

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log

private const val TAG = "ScreenAnalyzer"

data class AnalyzeResult(
    val hasGreenIcon: Boolean = false,
    val hasGroupBuyDot: Boolean = false,
    val likeCount: Long = 0,
    val commentCount: Long = 0,
    val favoriteCount: Long = 0,
    val shareCount: Long = 0,
    val isBeijing: Boolean = false,
    val meetsAllConditions: Boolean = false,
    val ocrText: String = ""
)

class ScreenAnalyzer {

    /**
     * 分析无障碍服务获取的屏幕文本节点
     * @param nodeTexts 屏幕上所有文本节点的内容列表
     * @param ratioPercent 比例条件百分比 (20/30/40/50)
     * @param minComments 最低评论数
     */
    fun analyzeFromTexts(
        nodeTexts: List<String>,
        ratioPercent: Int,
        minComments: Int
    ): AnalyzeResult {
        val fullText = nodeTexts.joinToString("\n")
        Log.d(TAG, "Analyzing texts: $fullText")

        // 检测北京地区
        val isBeijing = nodeTexts.any { it.contains("北京") }

        // 检测绿标/团购相关
        // 抖音团购视频通常有: "优惠团购"、"已售XX万+"、"免预约" 等文字
        val hasGroupBuy = nodeTexts.any {
            it.contains("团购") || it.contains("已售") ||
            it.contains("免预约") || it.contains("优惠") ||
            it.contains("到店") || it.contains("券")
        }

        // 提取互动数据
        // 抖音右侧栏从上到下: 点赞、评论、收藏、转发
        var likeCount = 0L
        var commentCount = 0L
        var favoriteCount = 0L
        var shareCount = 0L

        for (text in nodeTexts) {
            val num = CountParser.parseLong(text)
            // 纯数字或带万的文本，根据上下文判断
            if (num > 0) {
                // 尝试根据相邻文本或特征判断
                when {
                    text.contains("赞") || text.contains("喜欢") -> likeCount = num
                    text.contains("评论") -> commentCount = num
                    text.contains("收藏") -> favoriteCount = num
                    text.contains("转发") || text.contains("分享") -> shareCount = num
                }
            }
        }

        // 如果没有通过关键词匹配到，尝试按位置顺序解析纯数字
        // 无障碍服务获取的节点通常有 content-description
        if (likeCount == 0L || commentCount == 0L) {
            val numbers = nodeTexts.mapNotNull { text ->
                val n = CountParser.parseLong(text)
                if (n > 0 && Regex("^[\\d.万wW,]+$").matches(text.trim())) n else null
            }
            // 抖音右侧栏顺序: 点赞、评论、收藏、转发
            if (numbers.size >= 2) {
                if (likeCount == 0L) likeCount = numbers.getOrElse(0) { 0 }
                if (commentCount == 0L) commentCount = numbers.getOrElse(1) { 0 }
                if (favoriteCount == 0L) favoriteCount = numbers.getOrElse(2) { 0 }
                if (shareCount == 0L) shareCount = numbers.getOrElse(3) { 0 }
            }
        }

        // 评论数条件
        val meetsComments = commentCount >= minComments

        // 比例条件: min(like/share, share/like) >= ratioPercent%
        val meetsRatio = checkRatio(likeCount, shareCount, ratioPercent)

        val meetsAll = isBeijing && hasGroupBuy && meetsComments && meetsRatio

        Log.d(TAG, "Result: beijing=$isBeijing, groupBuy=$hasGroupBuy, " +
            "like=$likeCount, comment=$commentCount, fav=$favoriteCount, share=$shareCount, " +
            "meetsComments=$meetsComments, meetsRatio=$meetsRatio, meetsAll=$meetsAll")

        return AnalyzeResult(
            hasGreenIcon = isBeijing && hasGroupBuy, // 绿标通常伴随地区+团购
            hasGroupBuyDot = hasGroupBuy,
            likeCount = likeCount,
            commentCount = commentCount,
            favoriteCount = favoriteCount,
            shareCount = shareCount,
            isBeijing = isBeijing,
            meetsAllConditions = meetsAll,
            ocrText = fullText
        )
    }

    /**
     * 检查点赞/转发比例
     * share/like 或 like/share >= ratioPercent%
     */
    private fun checkRatio(like: Long, share: Long, ratioPercent: Int): Boolean {
        if (like == 0L || share == 0L) return false
        val ratio = if (share > like) {
            like.toDouble() / share.toDouble()
        } else {
            share.toDouble() / like.toDouble()
        }
        return ratio >= ratioPercent / 100.0
    }
}
