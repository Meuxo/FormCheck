package com.meuxo.formcheck.domain.usecase

import com.meuxo.formcheck.domain.model.AnalysisResult
import com.meuxo.formcheck.domain.repository.SessionRepository
import javax.inject.Inject

class SaveSessionUseCase @Inject constructor(
    private val repository: SessionRepository
) {
    suspend operator fun invoke(result: AnalysisResult): Long =
        repository.saveSession(result)
}
