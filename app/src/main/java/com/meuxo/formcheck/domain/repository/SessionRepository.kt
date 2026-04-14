package com.meuxo.formcheck.domain.repository

import com.meuxo.formcheck.domain.model.AnalysisResult
import com.meuxo.formcheck.domain.model.Session
import kotlinx.coroutines.flow.Flow

interface SessionRepository {
    suspend fun saveSession(result: AnalysisResult): Long
    fun getSession(id: Long): Flow<Session?>
    fun getRecentSessions(limit: Int): Flow<List<Session>>
    fun getAllSessions(): Flow<List<Session>>
    suspend fun deleteSession(id: Long)
}
