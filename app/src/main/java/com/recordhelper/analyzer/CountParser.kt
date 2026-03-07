package com.recordhelper.analyzer

/**
 * 解析互动数据文本，如 "1.2万赞"、"3456评论" 等
 */
object CountParser {

    fun parse(text: String): String {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return "0"

        // 匹配 "1.2万" 或 "1.2w" 格式
        val wanPattern = Regex("([\\d.]+)\\s*[万wW]")
        wanPattern.find(cleaned)?.let {
            val num = it.groupValues[1].toDoubleOrNull() ?: return cleaned
            return (num * 10000).toLong().toString()
        }

        // 匹配纯数字
        val numPattern = Regex("([\\d,]+)")
        numPattern.find(cleaned)?.let {
            return it.groupValues[1].replace(",", "")
        }

        return cleaned
    }
}
