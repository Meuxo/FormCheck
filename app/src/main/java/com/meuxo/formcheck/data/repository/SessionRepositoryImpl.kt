package com.meuxo.formcheck.data.repository

import com.meuxo.formcheck.data.local.MetricEntity
import com.meuxo.formcheck.data.local.SessionDao
import com.meuxo.formcheck.data.local.SessionEntity
import com.meuxo.formcheck.data.local.ShotEntity
import com.meuxo.formcheck.data.storage.SessionFiles
import com.meuxo.formcheck.domain.model.Metric
import com.meuxo.formcheck.domain.model.MetricId
import com.meuxo.formcheck.domain.model.Session
import com.meuxo.formcheck.domain.model.SessionParams
import com.meuxo.formcheck.domain.model.SessionResult
import com.meuxo.formcheck.domain.model.SessionStatus
import com.meuxo.formcheck.domain.model.SessionType
import com.meuxo.formcheck.domain.model.ShotOutcome
import com.meuxo.formcheck.domain.repository.SessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionRepositoryImpl @Inject constructor(
    private val dao: SessionDao,
    private val files: SessionFiles
) : SessionRepository {

    override fun observeAll(type: SessionType?): Flow<List<Session>> =
        (if (type == null) dao.observeAll() else dao.observeByType(type.name)).map { l -> l.map { it.toDomain() } }

    override fun observe(id: Long): Flow<Session?> = dao.observeSession(id).map { it?.toDomain() }

    override suspend fun get(id: Long): Session? = dao.getSession(id)?.toDomain()

    override suspend fun create(params: SessionParams, title: String?): Long {
        val id = dao.insertSession(
            SessionEntity(
                timestamp = System.currentTimeMillis(), type = params.type.name, title = title,
                status = SessionStatus.IMPORTING.name, statusMessage = null, dirPath = null, mediaPath = null,
                trackPath = null, thumbPath = null, resultPath = null, overallScore = null, includedMetrics = null,
                totalMetrics = null, makes = null, attempts = null, durationMs = null, sizeBytes = null,
                analyzedAt = null, legacyMediaUri = null, legacyJson = null
            )
        )
        files.writeParams(id, params)
        return id
    }

    override suspend fun setMedia(id: Long, sizeBytes: Long, durationMs: Long?) {
        dao.setMedia(id, files.dir(id).absolutePath, files.media(id).absolutePath, sizeBytes, durationMs)
        dao.setStatus(id, SessionStatus.PENDING.name, null)
    }

    override suspend fun setStatus(id: Long, status: SessionStatus, message: String?) =
        dao.setStatus(id, status.name, message)

    override suspend fun saveAnalysis(id: Long, result: SessionResult, hasThumb: Boolean) {
        files.writeResult(id, result)
        val ts = dao.getSession(id)?.timestamp ?: System.currentTimeMillis()
        val metricRows = (result.metrics + result.shots.flatMap { it.metrics }).map { it.toEntity(id, ts) }
        val shotRows = result.shots.map { s ->
            ShotEntity(sessionId = id, shotIndex = s.index, startFrame = s.startFrame, releaseFrame = s.releaseFrame,
                endFrame = s.endFrame, outcome = s.outcome?.name, outcomeCorrected = s.outcomeCorrected,
                courtX = s.courtX, courtY = s.courtY, zone = s.zone?.name, overallScore = s.overall?.percent)
        }
        dao.replaceAnalysis(id, metricRows, shotRows)
        dao.setResult(
            id = id, status = SessionStatus.DONE.name, track = files.track(id).absolutePath,
            thumb = if (hasThumb) files.thumb(id).absolutePath else null, result = files.result(id).absolutePath,
            overall = result.overall.percent, included = result.overall.included, total = result.overall.total,
            makes = result.metrics.firstOrNull { it.id == MetricId.MAKES }?.value?.toInt(),
            attempts = result.metrics.firstOrNull { it.id == MetricId.ATTEMPTS }?.value?.toInt(),
            size = files.sizeOf(id), analyzedAt = System.currentTimeMillis()
        )
    }

    override suspend fun rename(id: Long, title: String?) = dao.rename(id, title?.takeIf { it.isNotBlank() })

    override suspend fun delete(id: Long) {
        dao.deleteSession(id)
        files.delete(id)
    }

    override suspend fun correctOutcome(id: Long, shotIndex: Int, outcome: ShotOutcome) {
        dao.correctOutcome(id, shotIndex, outcome.name)
        val r = files.readResult(id) ?: return
        val shots = r.shots.map { if (it.index == shotIndex) it.copy(outcome = outcome, outcomeCorrected = true) else it }
        saveAnalysis(id, r.copy(shots = shots), files.thumb(id).exists())
    }

    override fun observeTotalBytes(): Flow<Long> = dao.observeTotalBytes()
    override fun observeCorrectionCount(): Flow<Int> = dao.observeCorrectionCount()
    override fun observeMetricHistory(metricId: String) = dao.observeMetricHistory(metricId)
    override fun observeAllMeasuredMetrics() = dao.observeAllMeasuredMetrics()
    override fun observeAllShots() = dao.observeAllShots()

    private fun Metric.toEntity(sessionId: Long, ts: Long) = MetricEntity(
        sessionId = sessionId, timestamp = ts, shotIndex = shotIndex, metricId = id.name, value = value,
        unit = unit.name, confidence = confidence, score = score, frameStart = evidence?.frameStart,
        frameEnd = evidence?.frameEnd, keyFrame = evidence?.keyFrame, reason = notMeasuredReason, note = note
    )

    private fun SessionEntity.toDomain() = Session(
        id = id, timestamp = timestamp,
        type = runCatching { SessionType.valueOf(type) }.getOrDefault(SessionType.FORM),
        title = title,
        status = runCatching { SessionStatus.valueOf(status) }.getOrDefault(SessionStatus.FAILED),
        statusMessage = statusMessage, mediaPath = mediaPath, thumbPath = thumbPath, resultPath = resultPath,
        trackPath = trackPath, overallScore = overallScore, includedMetrics = includedMetrics,
        totalMetrics = totalMetrics, makes = makes, attempts = attempts, durationMs = durationMs,
        sizeBytes = sizeBytes, legacyMediaUri = legacyMediaUri
    )
}
