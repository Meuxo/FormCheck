package com.meuxo.formcheck.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Insert
    suspend fun insertSession(session: SessionEntity): Long

    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1")
    fun observeSession(id: Long): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1")
    suspend fun getSession(id: Long): SessionEntity?

    @Query("SELECT * FROM sessions ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE type = :type ORDER BY timestamp DESC")
    fun observeByType(type: String): Flow<List<SessionEntity>>

    @Query("UPDATE sessions SET status = :status, statusMessage = :message WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, message: String?)

    @Query("UPDATE sessions SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String?)

    @Query("UPDATE sessions SET dirPath = :dir, mediaPath = :media, sizeBytes = :size, durationMs = :durationMs WHERE id = :id")
    suspend fun setMedia(id: Long, dir: String, media: String, size: Long, durationMs: Long?)

    @Query(
        "UPDATE sessions SET status = :status, statusMessage = NULL, trackPath = :track, thumbPath = :thumb, " +
            "resultPath = :result, overallScore = :overall, includedMetrics = :included, totalMetrics = :total, " +
            "makes = :makes, attempts = :attempts, sizeBytes = :size, analyzedAt = :analyzedAt WHERE id = :id"
    )
    suspend fun setResult(
        id: Long, status: String, track: String, thumb: String?, result: String,
        overall: Int?, included: Int, total: Int, makes: Int?, attempts: Int?, size: Long, analyzedAt: Long
    )

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)

    @Insert
    suspend fun insertMetrics(metrics: List<MetricEntity>)

    @Query("DELETE FROM metrics WHERE sessionId = :sessionId")
    suspend fun deleteMetrics(sessionId: Long)

    @Insert
    suspend fun insertShots(shots: List<ShotEntity>)

    @Query("DELETE FROM shots WHERE sessionId = :sessionId")
    suspend fun deleteShots(sessionId: Long)

    @Query("UPDATE shots SET outcome = :outcome, outcomeCorrected = 1 WHERE sessionId = :sessionId AND shotIndex = :shotIndex")
    suspend fun correctOutcome(sessionId: Long, shotIndex: Int, outcome: String)

    @Query("SELECT COUNT(*) FROM shots WHERE outcomeCorrected = 1")
    fun observeCorrectionCount(): Flow<Int>

    @Query("SELECT * FROM shots WHERE sessionId = :sessionId ORDER BY shotIndex")
    fun observeShots(sessionId: Long): Flow<List<ShotEntity>>

    @Query("SELECT * FROM metrics WHERE metricId = :metricId AND value IS NOT NULL ORDER BY timestamp ASC")
    fun observeMetricHistory(metricId: String): Flow<List<MetricEntity>>

    @Query("SELECT * FROM metrics WHERE value IS NOT NULL ORDER BY timestamp ASC")
    fun observeAllMeasuredMetrics(): Flow<List<MetricEntity>>

    @Query("SELECT * FROM shots")
    fun observeAllShots(): Flow<List<ShotEntity>>

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM sessions")
    fun observeTotalBytes(): Flow<Long>

    @Transaction
    suspend fun replaceAnalysis(sessionId: Long, metrics: List<MetricEntity>, shots: List<ShotEntity>) {
        deleteMetrics(sessionId); deleteShots(sessionId)
        insertMetrics(metrics); insertShots(shots)
    }
}
