package com.recordhelper.analyzer

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "VideoPageAnalyzer"

class VideoPageAnalyzer {

    private val recognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )

    suspend fun analyze(bitmap: Bitmap): AnalyzeResult {
        val hasLocationIcon = detectGreenIcon(bitmap)
        if (!hasLocationIcon) {
            return AnalyzeResult(isVideoPage = false)
        }
        val ocrText = performOcr(bitmap)
        Log.d(TAG, "OCR result: $ocrText")
        return parseOcrResult(ocrText)
    }

    private fun detectGreenIcon(bitmap: Bitmap): Boolean {
        val scanWidth = (bitmap.width * 0.3).toInt()
        val scanStartY = (bitmap.height * 0.7).toInt()
        var greenPixelCount = 0
        val threshold = 50
        for (x in 0 until scanWidth step 2) {
            for (y in scanStartY until bitmap.height step 2) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                if (g > 150 && g > r + 50 && g > b + 50) {
                    greenPixelCount++
                }
            }
        }
        Log.d(TAG, "Green pixel count: $greenPixelCount")
        return greenPixelCount > threshold
    }

    private suspend fun performOcr(bitmap: Bitmap): String {
        return suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result -> cont.resume(result.text) }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        }
    }

    /**
     * 解析 OCR 结果
     * 抖音右侧互动栏通常是：点赞数、评论数、收藏数、转发数（从上到下）
     * 判断条件：转发/点赞比例 >= 50%
     */
    private fun parseOcrResult(ocrText: String): AnalyzeResult {
        val lines = ocrText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

        var merchantName = ""
        var likeCount = 0L
        var commentCount = 0L
        var favoriteCount = 0L
        var shareCount = 0L

        for (line in lines) {
            if (line.startsWith("@") && merchantName.isEmpty()) {
                merchantName = line.removePrefix("@").trim()
            }
        }

        // 提取各项互动数据
        // 抖音 OCR 可能识别出数字在关键词附近
        for (i in lines.indices) {
            val line = lines[i]
            val num = CountParser.parseLong(line)

            when {
                line.contains("赞") || line.contains("喜欢") -> likeCount = num
                line.contains("评论") -> commentCount = num
                line.contains("收藏") || line.contains("⭐") -> favoriteCount = num
                line.contains("转发") || line.contains("分享") -> shareCount = num
            }

            // 抖音右侧数字通常是纯数字行，紧跟在图标后
            // 如果当前行是纯数字，尝试根据位置推断
            if (num > 0 && likeCount == 0L && !line.contains("评论")
                && !line.contains("收藏") && !line.contains("转发")) {
                // 第一个遇到的纯数字可能是点赞
                if (Regex("^[\\d.万wW,]+$").matches(line)) {
                    likeCount = num
                }
            }
        }

        // 转发/点赞比例判断：3:4 到 4:3 之间
        val meetsRatio = checkShareLikeRatio(shareCount, likeCount)

        val interactionSummary = "赞$likeCount 评$commentCount 藏$favoriteCount 转$shareCount"
        Log.d(TAG, "Parsed: merchant=$merchantName, $interactionSummary, ratio=$meetsRatio")

        return AnalyzeResult(
            merchantName = merchantName,
            likeCount = likeCount,
            commentCount = commentCount,
            favoriteCount = favoriteCount,
            shareCount = shareCount,
            interactionCount = interactionSummary,
            isVideoPage = merchantName.isNotEmpty(),
            meetsRatioCondition = meetsRatio,
            ocrText = ocrText
        )
    }

    /**
     * 检查转发/点赞比例是否 >= 50%
     * 即 share/like >= 0.5
     */
    private fun checkShareLikeRatio(share: Long, like: Long): Boolean {
        if (like == 0L || share == 0L) return false
        val ratio = share.toDouble() / like.toDouble()
        return ratio >= 0.5
    }
}
