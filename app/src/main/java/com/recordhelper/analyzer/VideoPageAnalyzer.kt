package com.recordhelper.analyzer

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 视频页面分析器
 * 先扫描左下角绿色像素检测地址图标，再用 ML Kit 中文 OCR 提取商家名和互动数据，按规则判断命中
 */
class VideoPageAnalyzer {

    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    /**
     * 分析截图，判断是否为视频页面并提取信息
     */
    suspend fun analyze(bitmap: Bitmap): AnalyzeResult {
        // 1. 检测左下角是否有绿色地址图标
        val hasLocationIcon = detectGreenIcon(bitmap)
        if (!hasLocationIcon) {
            return AnalyzeResult(isVideoPage = false)
        }

        // 2. OCR 识别文字
        val ocrText = performOcr(bitmap)

        // 3. 提取商家名和互动数据
        return parseOcrResult(ocrText)
    }

    /**
     * 扫描左下角区域检测绿色像素（地址图标）
     */
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
                // 绿色判定：G通道明显高于R和B
                if (g > 150 && g > r + 50 && g > b + 50) {
                    greenPixelCount++
                }
            }
        }
        return greenPixelCount > threshold
    }

    /**
     * 使用 ML Kit 中文 OCR 识别文字
     */
    private suspend fun performOcr(bitmap: Bitmap): String {
        return suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    cont.resume(result.text)
                }
                .addOnFailureListener { e ->
                    cont.resumeWithException(e)
                }
        }
    }

    /**
     * 解析 OCR 结果，提取商家名和互动数
     */
    private fun parseOcrResult(ocrText: String): AnalyzeResult {
        val lines = ocrText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

        var merchantName = ""
        var interactionCount = ""

        // 简单规则：查找 @ 开头的商家名
        for (line in lines) {
            if (line.startsWith("@") && merchantName.isEmpty()) {
                merchantName = line.removePrefix("@").trim()
            }
            // 查找包含赞、评论、转发等关键词的互动数据
            if (line.contains("赞") || line.contains("评论") || line.contains("转发") || line.contains("收藏")) {
                interactionCount = CountParser.parse(line)
            }
        }

        return AnalyzeResult(
            merchantName = merchantName,
            interactionCount = interactionCount,
            isVideoPage = merchantName.isNotEmpty()
        )
    }
}
