package com.recordhelper.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoRecordDao {
    @Query("SELECT * FROM video_records ORDER BY createdAt DESC")
    fun getAllRecords(): Flow<List<VideoRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: VideoRecordEntity): Long

    @Update
    suspend fun update(record: VideoRecordEntity)

    @Delete
    suspend fun delete(record: VideoRecordEntity)

    @Query("DELETE FROM video_records")
    suspend fun deleteAll()
}
