package com.meuxo.formcheck.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val mediaUri: String,
    val overallScore: Int,
    val elbowFlareScore: Int,
    val elbowAngleAtRelease: Float,
    val kneeBendDepth: Float,
    val balanceScore: Int,
    val guideHandScore: Int,
    val phaseFeedbackJson: String
)
