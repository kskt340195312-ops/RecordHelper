package com.recordhelper.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "video_records")
data class VideoRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val merchantName: String = "",
    val interactionCount: String = "",
    val publishTime: String = "",
    val analyzedPublishTime: String = "",
    val screenshotPath: String = "",
    val status: RecordStatus = RecordStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis()
)
