package com.meuxo.formcheck.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Analyzers and trackers have @Inject constructors, so Hilt provides them directly. Pure
 * analysis classes (tracking/, analysis/, scoring/, shootaround/, dribble/, jump/) take plain
 * data and are unit tested without DI; this module is where test doubles would be bound.
 */
@Module
@InstallIn(SingletonComponent::class)
object AnalysisModule
