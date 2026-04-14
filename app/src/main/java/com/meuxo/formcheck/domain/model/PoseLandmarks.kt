package com.meuxo.formcheck.domain.model

import android.graphics.PointF

/**
 * Strongly-typed projection of the 33 ML Kit pose landmarks we care about
 * for shooting-form analysis. Coordinates are in image pixels.
 */
data class PoseLandmarks(
    val nose: PointF,
    val leftEye: PointF,
    val rightEye: PointF,
    val leftEar: PointF,
    val rightEar: PointF,
    val leftShoulder: PointF,
    val rightShoulder: PointF,
    val leftElbow: PointF,
    val rightElbow: PointF,
    val leftWrist: PointF,
    val rightWrist: PointF,
    val leftIndex: PointF,
    val rightIndex: PointF,
    val leftHip: PointF,
    val rightHip: PointF,
    val leftKnee: PointF,
    val rightKnee: PointF,
    val leftAnkle: PointF,
    val rightAnkle: PointF,
    /** Approximate forehead Y derived from nose/eyes. */
    val foreheadY: Float,
    /** Confidence average across the tracked landmarks (0..1). */
    val confidence: Float = 1f
)
