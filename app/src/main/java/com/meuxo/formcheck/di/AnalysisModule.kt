package com.meuxo.formcheck.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Analysis classes are all @Singleton with @Inject constructors, so Hilt
 * provides them automatically. This module is a placeholder for future
 * @Provides bindings (e.g. swapping in a fake PoseEstimator under test).
 */
@Module
@InstallIn(SingletonComponent::class)
object AnalysisModule
