package com.meuxo.formcheck.domain.repository

import com.meuxo.formcheck.domain.model.Session
import com.meuxo.formcheck.domain.model.SessionParams
import com.meuxo.formcheck.domain.model.SessionResult
import com.meuxo.formcheck.domain.model.SessionStatus
import com.meuxo.formcheck.domain.model.SessionType
import com.meuxo.formcheck.domain.model.ShotOutcome
import com.meuxo.formcheck.data.local.MetricEntity
import com.meuxo.formcheck.data.local.ShotEntity
import kotlinx.coroutines.flow.Flow

interface SessionRepository {
    fun observeAll(type: SessionType? = null): Flow<List<Session>>
    fun observe(id: Long): Flow<Session?>
    suspend fun get(id: Long): Session?

    /** Inserts a session row in IMPORTING state and writes its params. */
    suspend fun create(params: SessionParams, title: String? = null): Long
    suspend fun setMedia(id: Long, sizeBytes: Long, durationMs: Long?)
    suspend fun setStatus(id: Long, status: SessionStatus, message: String? = null)
    suspend fun saveAnalysis(id: Long, result: SessionResult, hasThumb: Boolean)
    suspend fun rename(id: Long, title: String?)
    suspend fun delete(id: Long)
    suspend fun correctOutcome(id: Long, shotIndex: Int, outcome: ShotOutcome)

    fun observeTotalBytes(): Flow<Long>
    fun observeCorrectionCount(): Flow<Int>
    fun observeMetricHistory(metricId: String): Flow<List<MetricEntity>>
    fun observeAllMeasuredMetrics(): Flow<List<MetricEntity>>
    fun observeAllShots(): Flow<List<ShotEntity>>
}
