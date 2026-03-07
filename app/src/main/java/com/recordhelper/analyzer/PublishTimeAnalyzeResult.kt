package com.recordhelper.analyzer

data class PublishTimeAnalyzeResult(
    val rawText: String = "",
    val parsedTime: String = "",
    val confidence: Float = 0f
)
