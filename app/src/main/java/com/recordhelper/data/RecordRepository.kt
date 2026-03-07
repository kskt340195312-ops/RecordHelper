package com.recordhelper.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

class RecordRepository(context: Context) {
    private val dao = AppDatabase.getInstance(context).videoRecordDao()

    fun getAllRecords(): Flow<List<VideoRecordEntity>> = dao.getAllRecords()

    suspend fun insert(record: VideoRecordEntity): Long = dao.insert(record)

    suspend fun update(record: VideoRecordEntity) = dao.update(record)

    suspend fun delete(record: VideoRecordEntity) = dao.delete(record)

    suspend fun deleteAll() = dao.deleteAll()
}
