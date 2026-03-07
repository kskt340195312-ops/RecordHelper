package com.recordhelper.analyzer

import java.text.SimpleDateFormat
import java.util.*

/**
 * 发布时间分析器
 * 用多个正则匹配各种中文时间格式（2025-03-01、3天前、昨天等）
 */
object PublishTimeAnalyzer {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

    fun analyze(text: String): PublishTimeAnalyzeResult {
        val now = Calendar.getInstance()

        // 匹配 "X分钟前"
        Regex("(\\d+)\\s*分钟前").find(text)?.let {
            val minutes = it.groupValues[1].toIntOrNull() ?: 0
            now.add(Calendar.MINUTE, -minutes)
            return PublishTimeAnalyzeResult(
                rawText = it.value,
                parsedTime = dateFormat.format(now.time),
                confidence = 0.9f
            )
        }

        // 匹配 "X小时前"
        Regex("(\\d+)\\s*小时前").find(text)?.let {
            val hours = it.groupValues[1].toIntOrNull() ?: 0
            now.add(Calendar.HOUR_OF_DAY, -hours)
            return PublishTimeAnalyzeResult(
                rawText = it.value,
                parsedTime = dateFormat.format(now.time),
                confidence = 0.9f
            )
        }

        // 匹配 "X天前"
        Regex("(\\d+)\\s*天前").find(text)?.let {
            val days = it.groupValues[1].toIntOrNull() ?: 0
            now.add(Calendar.DAY_OF_YEAR, -days)
            return PublishTimeAnalyzeResult(
                rawText = it.value,
                parsedTime = dateFormat.format(now.time),
                confidence = 0.85f
            )
        }

        // 匹配 "昨天"
        if (text.contains("昨天")) {
            now.add(Calendar.DAY_OF_YEAR, -1)
            return PublishTimeAnalyzeResult(
                rawText = "昨天",
                parsedTime = dateFormat.format(now.time),
                confidence = 0.95f
            )
        }

        // 匹配 "前天"
        if (text.contains("前天")) {
            now.add(Calendar.DAY_OF_YEAR, -2)
            return PublishTimeAnalyzeResult(
                rawText = "前天",
                parsedTime = dateFormat.format(now.time),
                confidence = 0.95f
            )
        }

        // 匹配 "刚刚"
        if (text.contains("刚刚")) {
            return PublishTimeAnalyzeResult(
                rawText = "刚刚",
                parsedTime = dateFormat.format(now.time),
                confidence = 0.95f
            )
        }

        // 匹配标准日期格式 yyyy-MM-dd 或 yyyy/MM/dd 或 yyyy.MM.dd
        Regex("(\\d{4})[\\-/\\.](\\d{1,2})[\\-/\\.](\\d{1,2})").find(text)?.let {
            return PublishTimeAnalyzeResult(
                rawText = it.value,
                parsedTime = "${it.groupValues[1]}-${it.groupValues[2].padStart(2, '0')}-${it.groupValues[3].padStart(2, '0')}",
                confidence = 0.95f
            )
        }

        // 匹配 "MM-dd" 格式（无年份，默认当年）
        Regex("(\\d{1,2})[\\-/\\.](\\d{1,2})").find(text)?.let {
            val year = now.get(Calendar.YEAR)
            return PublishTimeAnalyzeResult(
                rawText = it.value,
                parsedTime = "$year-${it.groupValues[1].padStart(2, '0')}-${it.groupValues[2].padStart(2, '0')}",
                confidence = 0.7f
            )
        }

        return PublishTimeAnalyzeResult(rawText = text, parsedTime = "", confidence = 0f)
    }
}
