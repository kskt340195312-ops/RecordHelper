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

/**
 * 抖音同城页面节点结构（从debug_nodes确认）：
 *
 * [顶部导航] 精选 热点 经验 关注 北京 直播 团购 商城 推荐
 * [ViewPager desc=视频]
 *   [视频1] 关注 → 头像 → 喜欢X → 评论X → 收藏X → 分享X → 音乐 → [全屏观看]
 *           → [北京市 | 店名 · 优惠团购] → @作者 → 描述 → [相关搜索] → 暂停/播放视频
 *   [视频2] 关注 → 头像 → 喜欢X → 评论X → 收藏X → 分享X → 音乐
 *           → [北京市 | 店名 · 优惠团购] → @作者 → 描述 → 暂停/播放视频
 *   ...
 * [底部导航] 首页 朋友 拍摄 消息 我
 *
 * 注意：顶部导航的"北京"和"团购"是tab按钮，不是视频内容！
 */
class ScreenAnalyzer {

    fun analyze(
        nodeInfos: List<NodeData>,
        bitmap: Bitmap?,
        ratioPercent: Int,
        minComments: Int
    ): AnalyzeResult {
        // 把节点按视频分组
        val videos = splitIntoVideos(nodeInfos)

        // 找当前正在播放的视频（含"暂停视频"标记的那个）
        var currentVideo = videos.firstOrNull { segment ->
            segment.any { (it.contentDesc ?: "").contains("暂停视频") }
        }
        // 如果没找到"暂停视频"，用第一个视频
        if (currentVideo == null) currentVideo = videos.firstOrNull()

        if (currentVideo == null) {
            return AnalyzeResult(debugInfo = "无法识别视频节点")
        }

        val segTexts = currentVideo.flatMap { listOfNotNull(it.text, it.contentDesc) }

        // === 条件1: 绿标 - 像素检测 ===
        val hasGreenIcon = if (bitmap != null) detectGreenIcon(bitmap) else false

        // === 条件2: 团购文字 ===
        // 只在视频内容中找，排除顶部导航tab（tab的desc格式是"团购，按钮"且class是TextView）
        // 视频内的团购文字是 "优惠团购" 或 "已售XX万+"
        val hasGroupBuyText = currentVideo.any { node ->
            val t = node.text ?: ""
            val d = node.contentDesc ?: ""
            // 排除顶部导航tab（desc格式为"团购，按钮"且text就是"团购"）
            val isNavTab = d.matches(Regex("团购，按钮"))
            !isNavTab && (t.contains("优惠团购") || t.contains("已售") ||
                (t.contains("团购") && t.length > 2)) // "团购"两字单独出现是tab，"优惠团购"/"X款团购"才是内容
        }

        // === 条件3: 北京地区 ===
        // 视频内的北京是 "北京市" 或描述中包含北京，排除顶部tab（desc="已选中，北京，按钮"）
        val isBeijing = currentVideo.any { node ->
            val t = node.text ?: ""
            val d = node.contentDesc ?: ""
            val isNavTab = d.contains("已选中") && d.contains("北京")
            !isNavTab && (t.contains("北京市") || t.contains("北京") && t.length > 2)
        }

        // === 提取互动数据 ===
        val counts = extractCounts(currentVideo)

        val meetsComments = counts.comment >= minComments
        val meetsRatio = checkRatio(counts.like, counts.share, ratioPercent)
        val meetsAll = hasGreenIcon && hasGroupBuyText && isBeijing && meetsComments && meetsRatio

        val debug = buildString {
            appendLine("绿标=$hasGreenIcon 团购=$hasGroupBuyText 北京=$isBeijing")
            appendLine("赞=${counts.like} 评=${counts.comment} 藏=${counts.favorite} 转=${counts.share}")
            appendLine("评论>=$minComments:$meetsComments 比例>=${ratioPercent}%:$meetsRatio")
            appendLine("视频数=${videos.size} 当前段=${currentVideo.size}节点")
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

    /**
     * 把节点列表按视频分组。
     * 每个视频以 "暂停视频" 或 "播放视频" 结尾。
     * 每个视频以 "关注" 按钮（紧跟在ViewPager或上一个视频之后）开头。
     */
    private fun splitIntoVideos(nodeInfos: List<NodeData>): List<List<NodeData>> {
        val videos = mutableListOf<List<NodeData>>()
        var currentStart = -1

        for (i in nodeInfos.indices) {
            val desc = nodeInfos[i].contentDesc ?: ""

            // 视频开始标记：在"喜欢"节点之前的"关注"按钮
            // 但更可靠的是用"喜欢"节点作为视频的核心标记
            if (desc.contains("喜欢") && desc.contains("按钮") && currentStart < 0) {
                // 往前找到"关注"按钮作为起点
                currentStart = i
                for (j in i downTo maxOf(0, i - 3)) {
                    val d = nodeInfos[j].contentDesc ?: ""
                    if (d == "关注" || d.contains("关注，按钮")) {
                        currentStart = j
                        break
                    }
                }
            }

            // 视频结束标记："暂停视频" 或 "播放视频"
            if (currentStart >= 0 && (desc.contains("暂停视频") || desc.contains("播放视频"))) {
                videos.add(nodeInfos.subList(currentStart, i + 1))
                currentStart = -1
            }
        }

        // 如果最后一个视频没有结束标记，也加进去
        if (currentStart >= 0) {
            videos.add(nodeInfos.subList(currentStart, nodeInfos.size))
        }

        Log.d(TAG, "Split into ${videos.size} videos")
        return videos
    }

    private data class Counts(
        val like: Long = 0, val comment: Long = 0,
        val favorite: Long = 0, val share: Long = 0
    )

    /**
     * 从视频段落提取互动数据。只看 contentDescription。
     * 格式: "未点赞，喜欢3393，按钮" / "评论1485，按钮" / "未选中，收藏781，按钮" / "分享292，按钮"
     */
    private fun extractCounts(segment: List<NodeData>): Counts {
        var like = 0L; var comment = 0L; var favorite = 0L; var share = 0L

        for (node in segment) {
            val desc = node.contentDesc ?: continue

            if (like == 0L && desc.contains("喜欢")) {
                like = extractNum(desc, "喜欢")
            }
            if (comment == 0L && desc.startsWith("评论") && !desc.contains("评论评论")) {
                comment = extractNum(desc, "评论")
            }
            if (favorite == 0L && desc.contains("收藏")) {
                favorite = extractNum(desc, "收藏")
            }
            if (share == 0L && (desc.startsWith("分享") || desc.startsWith("转发")) && desc.contains("按钮")) {
                share = if (desc.startsWith("分享")) extractNum(desc, "分享")
                        else extractNum(desc, "转发")
            }
        }
        return Counts(like, comment, favorite, share)
    }

    /** 提取关键词后面的数字: "喜欢3393" → 3393, "喜欢1.5万" → 15000 */
    private fun extractNum(text: String, keyword: String): Long {
        val idx = text.indexOf(keyword)
        if (idx < 0) return 0
        val after = text.substring(idx + keyword.length)
        val m = Regex("[，,\\s]*([\\d.]+[万wW]?)").find(after) ?: return 0
        return CountParser.parseLong(m.groupValues[1])
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
