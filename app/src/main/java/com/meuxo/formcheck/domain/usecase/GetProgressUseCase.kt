package com.meuxo.formcheck.domain.usecase

import com.meuxo.formcheck.domain.model.MetricSnapshot
import com.meuxo.formcheck.domain.repository.SessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class GetProgressUseCase @Inject constructor(
    private val repository: SessionRepository
) {
    operator fun invoke(): Flow<List<MetricSnapshot>> =
        repository.getAllSessions().map { sessions ->
            sessions.takeLast(30).map { s ->
                MetricSnapshot(
                    timestamp = s.timestamp,
                    overallScore = s.overallScore,
                    elbowAngleAtRelease = s.elbowAngleAtRelease,
                    kneeBendDepth = s.kneeBendDepth,
                    balanceScore = s.balanceScore
                )
            }
        }
}
