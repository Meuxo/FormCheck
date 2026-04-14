package com.meuxo.formcheck.data.repository

import com.meuxo.formcheck.data.local.SessionDao
import com.meuxo.formcheck.data.local.SessionEntity
import com.meuxo.formcheck.domain.model.AnalysisResult
import com.meuxo.formcheck.domain.model.PhaseFeedback
import com.meuxo.formcheck.domain.model.Session
import com.meuxo.formcheck.domain.model.ShotPhase
import com.meuxo.formcheck.domain.repository.SessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionRepositoryImpl @Inject constructor(
    private val dao: SessionDao
) : SessionRepository {

    override suspend fun saveSession(result: AnalysisResult): Long {
        val entity = SessionEntity(
            timestamp = result.timestamp,
            mediaUri = result.mediaUri,
            overallScore = result.overallScore,
            elbowFlareScore = result.elbowFlareScore,
            elbowAngleAtRelease = result.elbowAngleAtRelease,
            kneeBendDepth = result.kneeBendDepth,
            balanceScore = result.balanceScore,
            guideHandScore = result.guideHandScore,
            phaseFeedbackJson = encode(result.phaseFeedback)
        )
        return dao.insertSession(entity)
    }

    override fun getSession(id: Long): Flow<Session?> =
        dao.getSession(id).map { it?.toDomain() }

    override fun getRecentSessions(limit: Int): Flow<List<Session>> =
        dao.getRecentSessions(limit).map { list -> list.map { it.toDomain() } }

    override fun getAllSessions(): Flow<List<Session>> =
        dao.getAllSessions().map { list -> list.map { it.toDomain() } }

    override suspend fun deleteSession(id: Long) = dao.deleteSession(id)

    // ---- Mapping helpers --------------------------------------------------

    private fun SessionEntity.toDomain(): Session = Session(
        id = id,
        timestamp = timestamp,
        mediaUri = mediaUri,
        overallScore = overallScore,
        elbowFlareScore = elbowFlareScore,
        elbowAngleAtRelease = elbowAngleAtRelease,
        kneeBendDepth = kneeBendDepth,
        balanceScore = balanceScore,
        guideHandScore = guideHandScore,
        phaseFeedback = decode(phaseFeedbackJson)
    )

    private fun encode(feedback: List<PhaseFeedback>): String {
        val arr = JSONArray()
        feedback.forEach { f ->
            val obj = JSONObject()
            obj.put("phase", f.phase.name)
            obj.put("score", f.score)
            val notes = JSONArray()
            f.notes.forEach { notes.put(it) }
            obj.put("notes", notes)
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun decode(json: String): List<PhaseFeedback> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val notesArr = obj.getJSONArray("notes")
                val notes = (0 until notesArr.length()).map { notesArr.getString(it) }
                PhaseFeedback(
                    phase = ShotPhase.valueOf(obj.getString("phase")),
                    score = obj.getInt("score"),
                    notes = notes
                )
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }
}
