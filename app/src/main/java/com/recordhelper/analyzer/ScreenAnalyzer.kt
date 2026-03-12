package com.recordhelper.analyzer

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
    val debugInfo: String = ""
)

class ScreenAnalyzer {

    /**
     * 分析无障碍服务获取的屏幕节点信息
     * @param nodeInfos 屏幕上所有节点的 (text, contentDescription) 对
     * @param ratioPercent 比例条件百分比 (20/30/40/50)
     * @param minComments 最低评论数
     */
    fun analyzeFromNodes(
        nodeInfos: List<NodeData>,
        ratioPercent: Int,
        minComments: Int
    ): AnalyzeResult {
        val allTexts = nodeInfos.flatMap {
            listOfNotNull(it.text, it.contentDesc)
        }
        val allDescs = nodeInfos.mapNotNull { it.contentDesc }

        Log.d(TAG, "=== Screen Analysis ===")
        Log.d(TAG, "All texts: $allTexts")

        // === 条件1: 检测北京地区 ===
        // 抖音团购视频底部会显示 "北京市｜xxx" 格式的地址
        val isBeijing = allTexts.any {
            it.contains("北京市") || it.contains("北京·")
        }
        Log.d(TAG, "isBeijing=$isBeijing")

        // === 条件2: 检测团购/绿标 ===
        // 抖音团购视频特征：底部有 "已售XX万+" 和商家名称带地址
        // 绿标 = 有定位图标 + 商家信息
        // contentDescription 中可能有 "团购" "已售" 等
        val hasGroupBuy = allTexts.any { text ->
            // 严格匹配：必须包含"已售"或"团购"
            text.contains("已售") || text.contains("团购")
        }
        Log.d(TAG, "hasGroupBuy=$hasGroupBuy")

        // === 提取互动数据 ===
        // 抖音无障碍节点中，互动按钮的 contentDescription 格式通常是:
        // "赞 9500" / "评论 23" / "收藏 201" / "转发 1000"
        // 或者 "点赞，9500" / "9500赞"
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

        // 也从 text 中尝试提取（有些版本数据在 text 里）
        for (info in nodeInfos) {
            val text = info.text ?: continue
            val lower = text.lowercase()
            when {
                (lower.contains("赞") || lower.contains("喜欢"))
                    && !lower.contains("评") && !lower.contains("收") && likeCount == 0L -> {
                    val n = CountParser.parseLong(text)
                    if (n > 0) likeCount = n
                }
                lower.contains("评论") && commentCount == 0L -> {
                    val n = CountParser.parseLong(text)
                    if (n > 0) commentCount = n
                }
                lower.contains("收藏") && favoriteCount == 0L -> {
                    val n = CountParser.parseLong(text)
                    if (n > 0) favoriteCount = n
                }
                (lower.contains("转发") || lower.contains("分享")) && shareCount == 0L -> {
                    val n = CountParser.parseLong(text)
                    if (n > 0) shareCount = n
                }
            }
        }

        Log.d(TAG, "Counts: like=$likeCount, comment=$commentCount, fav=$favoriteCount, share=$shareCount")

        // === 条件3: 评论数 ===
        val meetsComments = commentCount >= minComments
        Log.d(TAG, "meetsComments=$meetsComments (need>=$minComments, got=$commentCount)")

        // === 条件4: 比例条件 ===
        val meetsRatio = checkRatio(likeCount, shareCount, ratioPercent)
        Log.d(TAG, "meetsRatio=$meetsRatio (ratio=$ratioPercent%)")

        // 所有条件必须同时满足
        val meetsAll = isBeijing && hasGroupBuy && meetsComments && meetsRatio

        val debug = buildString {
            appendLine("北京=$isBeijing, 团购=$hasGroupBuy")
            appendLine("赞=$likeCount, 评=$commentCount, 藏=$favoriteCount, 转=$shareCount")
            appendLine("评论>=$minComments: $meetsComments")
            appendLine("比例>=${ratioPercent}%: $meetsRatio")
            appendLine("总判定: $meetsAll")
        }
        Log.d(TAG, "=== Result: meetsAll=$meetsAll ===")

        return AnalyzeResult(
            hasGreenIcon = hasGroupBuy && isBeijing,
            hasGroupBuyDot = hasGroupBuy,
            likeCount = likeCount,
            commentCount = commentCount,
            favoriteCount = favoriteCount,
            shareCount = shareCount,
            isBeijing = isBeijing,
            meetsAllConditions = meetsAll,
            debugInfo = debug
        )
    }

    private fun checkRatio(like: Long, share: Long, ratioPercent: Int): Boolean {
        if (like == 0L || share == 0L) return false
        val smaller = minOf(like, share).toDouble()
        val larger = maxOf(like, share).toDouble()
        val ratio = smaller / larger
        return ratio >= ratioPercent / 100.0
    }
}

data class NodeData(
    val text: String?,
    val contentDesc: String?,
    val className: String? = null
)
