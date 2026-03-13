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
        // === 找到当前视频的节点范围 ===
        // 抖音同城页面会同时加载多个视频的节点
        // 当前播放的视频有 "暂停视频" 标记，已暂停的有 "播放视频"
        // 我们需要找到当前视频对应的那组互动数据
        val videoSegment = findCurrentVideoSegment(nodeInfos)
        val segmentTexts = videoSegment.flatMap { listOfNotNull(it.text, it.contentDesc) }

        // === 条件1: 绿标 - 像素检测 ===
        val hasGreenIcon = if (bitmap != null) detectGreenIcon(bitmap) else false

        // === 条件2: 团购文字 — 只在当前视频段落中查找 ===
        val hasGroupBuyText = segmentTexts.any { it.contains("团购") || it.contains("已售") }

        // === 条件3: 北京地区 — 只在当前视频段落中查找 ===
        val isBeijing = segmentTexts.any { it.contains("北京") }

        // === 提取互动数据 — 只从当前视频段落提取 ===
        val counts = extractCountsFromSegment(videoSegment)

        val meetsComments = counts.comment >= minComments
        val meetsRatio = checkRatio(counts.like, counts.share, ratioPercent)
        val meetsAll = hasGreenIcon && hasGroupBuyText && isBeijing && meetsComments && meetsRatio

        val debug = buildString {
            appendLine("绿标=$hasGreenIcon 团购=$hasGroupBuyText 北京=$isBeijing")
            appendLine("赞=${counts.like} 评=${counts.comment} 藏=${counts.favorite} 转=${counts.share}")
            appendLine("评论>=$minComments:$meetsComments 比例>=${ratioPercent}%:$meetsRatio")
            appendLine("段落节点数=${videoSegment.size}/${nodeInfos.size} 方式=${counts.method}")
            appendLine("结果=$meetsAll")
        }
        Log.d(TAG, debug)

        return AnalyzeResult(
            hasGreenIcon = hasGreenIcon,
            hasGroupBuyText = hasGroupBuyText,
            likeCount = counts.like,
            commentCount = counts.comment,
            favoriteCount = counts.favorite,
            shareCount = counts.share,
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
     * 找到当前正在播放的视频对应的节点段落。
     *
     * 抖音同城页面的节点结构（从debug_nodes确认）：
     * 每个视频有一组节点：关注按钮、头像、喜欢、评论、收藏、分享、音乐、作者名、描述文字
     * 视频之间用 "暂停视频/播放视频" 节点分隔
     *
     * 策略：找到 "暂停视频" 节点（当前正在播放的），然后向前找到该视频的互动数据。
     * 如果找不到 "暂停视频"，就用第一组互动数据。
     */
    private fun findCurrentVideoSegment(nodeInfos: List<NodeData>): List<NodeData> {
        // 找所有 "喜欢" 节点的位置 — 每个视频都有一个 "喜欢XXX" 的 LinearLayout
        val likeIndices = mutableListOf<Int>()
        for (i in nodeInfos.indices) {
            val desc = nodeInfos[i].contentDesc ?: ""
            if (desc.contains("喜欢") && desc.contains("按钮")) {
                likeIndices.add(i)
            }
        }

        if (likeIndices.isEmpty()) return nodeInfos // fallback: 用全部节点

        // 找 "暂停视频" 节点 — 表示当前正在播放的视频
        var playingIndex = -1
        for (i in nodeInfos.indices) {
            val desc = nodeInfos[i].contentDesc ?: ""
            if (desc.contains("暂停视频")) {
                playingIndex = i
                break
            }
        }

        // 确定当前视频的 "喜欢" 节点
        var targetLikeIndex: Int
        if (playingIndex >= 0) {
            // 找 "暂停视频" 之前最近的 "喜欢" 节点
            targetLikeIndex = likeIndices.lastOrNull { it < playingIndex } ?: likeIndices[0]
        } else {
            // 没找到 "暂停视频"，用第一个
            targetLikeIndex = likeIndices[0]
        }

        // 确定段落范围：从 "喜欢" 节点往前找到 "关注" 按钮，往后找到下一个 "喜欢" 或文件末尾
        val segStart = findSegmentStart(nodeInfos, targetLikeIndex)
        val segEnd = if (likeIndices.indexOf(targetLikeIndex) < likeIndices.size - 1) {
            val nextLike = likeIndices[likeIndices.indexOf(targetLikeIndex) + 1]
            // 往前找到 "关注" 按钮
            findSegmentStart(nodeInfos, nextLike)
        } else {
            nodeInfos.size
        }

        Log.d(TAG, "Video segment: [$segStart, $segEnd) likeAt=$targetLikeIndex playingAt=$playingIndex")
        return nodeInfos.subList(segStart, minOf(segEnd, nodeInfos.size))
    }

    private fun findSegmentStart(nodeInfos: List<NodeData>, likeIndex: Int): Int {
        // 从 "喜欢" 节点往前找，找到 "关注" 按钮或 ViewPager
        for (i in likeIndex downTo 0) {
            val desc = nodeInfos[i].contentDesc ?: ""
            if (desc == "关注" || desc.contains("关注，按钮")) return i
            // ViewPager 是视频容器的标志
            if (nodeInfos[i].className?.contains("ViewPager") == true) return i
        }
        return 0
    }

    /**
     * 从一个视频段落的节点中提取互动数据。
     *
     * 抖音实际节点格式（从debug_nodes确认）：
     * - desc: "未点赞，喜欢3393，按钮"  → like=3393
     * - desc: "未点赞，喜欢1.5万，按钮" → like=15000
     * - desc: "评论1485，按钮"          → comment=1485
     * - desc: "评论评论，按钮"          → comment=0 (无评论时)
     * - desc: "未选中，收藏781，按钮"   → favorite=781
     * - desc: "分享292，按钮"           → share=292
     * - desc: "分享，按钮"              → share=0 (无分享时)
     */
    private fun extractCountsFromSegment(segment: List<NodeData>): Counts {
        var like = 0L
        var comment = 0L
        var favorite = 0L
        var share = 0L
        val methods = mutableListOf<String>()

        for (info in segment) {
            val desc = info.contentDesc ?: continue

            // 喜欢/点赞: "未点赞，喜欢3393，按钮" 或 "已点赞，喜欢3393，按钮"
            if (like == 0L && desc.contains("喜欢")) {
                val num = extractNumberAfterKeyword(desc, "喜欢")
                if (num > 0) { like = num; methods.add("赞:$desc") }
            }

            // 评论: "评论1485，按钮" — 注意 "评论评论" 表示0条评论
            if (comment == 0L && desc.contains("评论") && !desc.contains("喜欢") && !desc.contains("收藏") && !desc.contains("分享")) {
                val num = extractNumberAfterKeyword(desc, "评论")
                if (num > 0) { comment = num; methods.add("评:$desc") }
                // "评论评论" 或 "评论，按钮" 表示0条，不设置
            }

            // 收藏: "未选中，收藏781，按钮"
            if (favorite == 0L && desc.contains("收藏")) {
                val num = extractNumberAfterKeyword(desc, "收藏")
                if (num > 0) { favorite = num; methods.add("藏:$desc") }
            }

            // 分享: "分享292，按钮"
            if (share == 0L && desc.contains("分享") && !desc.contains("收藏")) {
                val num = extractNumberAfterKeyword(desc, "分享")
                if (num > 0) { share = num; methods.add("转:$desc") }
            }

            // 转发: 有些版本用"转发"而不是"分享"
            if (share == 0L && desc.contains("转发")) {
                val num = extractNumberAfterKeyword(desc, "转发")
                if (num > 0) { share = num; methods.add("转:$desc") }
            }
        }

        return Counts(like, comment, favorite, share, methods.joinToString("; ").ifEmpty { "none" })
    }

    /**
     * 从文本中提取关键词后面的数字。
     * 例如: "喜欢3393" → 3393, "喜欢1.5万" → 15000, "评论评论" → 0
     */
    private fun extractNumberAfterKeyword(text: String, keyword: String): Long {
        val idx = text.indexOf(keyword)
        if (idx < 0) return 0
        val after = text.substring(idx + keyword.length)
        // 提取紧跟关键词后面的数字（可能有分隔符）
        val numMatch = Regex("[，,\\s]*([\\d.]+[万wW]?)").find(after) ?: return 0
        return CountParser.parseLong(numMatch.groupValues[1])
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
