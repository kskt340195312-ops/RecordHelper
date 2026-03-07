package com.recordhelper.analyzer

data class AnalyzeResult(
    val merchantName: String = "",
    val likeCount: Long = 0,
    val commentCount: Long = 0,
    val favoriteCount: Long = 0,
    val shareCount: Long = 0,
    val interactionCount: String = "",
    val isVideoPage: Boolean = false,
    val meetsRatioCondition: Boolean = false,
    val ocrText: String = ""
)
