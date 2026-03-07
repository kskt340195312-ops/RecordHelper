package com.recordhelper.analyzer

object CountParser {

    fun parse(text: String): String {
        return parseLong(text).toString()
    }

    fun parseLong(text: String): Long {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return 0

        // "1.2万" or "1.2w"
        val wanPattern = Regex("([\\d.]+)\\s*[万wW]")
        wanPattern.find(cleaned)?.let {
            val num = it.groupValues[1].toDoubleOrNull() ?: return 0
            return (num * 10000).toLong()
        }

        // 纯数字（可能带逗号）
        val numPattern = Regex("([\\d,]+)")
        numPattern.find(cleaned)?.let {
            return it.groupValues[1].replace(",", "").toLongOrNull() ?: 0
        }

        return 0
    }
}
