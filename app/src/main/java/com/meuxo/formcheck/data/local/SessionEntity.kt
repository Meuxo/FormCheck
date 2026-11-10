package com.meuxo.formcheck.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One saved session. Media and tracking data live on disk in [dirPath]; Room keeps the
 * paths plus the numbers needed for the Library, Progress and search without opening files.
 */
@Entity(tableName = "sessions", indices = [Index("type"), Index("timestamp")])
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    /** [com.meuxo.formcheck.domain.model.SessionType] name. */
    val type: String,
    val title: String?,
    /** [com.meuxo.formcheck.domain.model.SessionStatus] name. */
    val status: String,
    val statusMessage: String?,
    val dirPath: String?,
    val mediaPath: String?,
    val trackPath: String?,
    val thumbPath: String?,
    val resultPath: String?,
    val overallScore: Int?,
    val includedMetrics: Int?,
    val totalMetrics: Int?,
    val makes: Int?,
    val attempts: Int?,
    val durationMs: Long?,
    val sizeBytes: Long?,
    val analyzedAt: Long?,
    /** v1 rows only: the original picker/MediaStore URI. */
    val legacyMediaUri: String?,
    /** v1 rows only: the v1 numbers, kept for transparency but never shown as scores. */
    val legacyJson: String?
)

/** Every metric of every analysed session, for Progress trends and personal bests. */
@Entity(
    tableName = "metrics",
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId"), Index("metricId")]
)
data class MetricEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val timestamp: Long,
    /** Null for session-level metrics. */
    val shotIndex: Int?,
    val metricId: String,
    val value: Double?,
    val unit: String,
    val confidence: Float,
    val score: Int?,
    val frameStart: Int?,
    val frameEnd: Int?,
    val keyFrame: Int?,
    val reason: String?,
    val note: String?
)

/** Shots (form clips and shootaround attempts). */
@Entity(
    tableName = "shots",
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId")]
)
data class ShotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val shotIndex: Int,
    val startFrame: Int,
    val releaseFrame: Int?,
    val endFrame: Int,
    val outcome: String?,
    val outcomeCorrected: Boolean,
    val courtX: Double?,
    val courtY: Double?,
    val zone: String?,
    val overallScore: Int?
)
