package com.meuxo.formcheck.util

import android.graphics.PointF
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import com.meuxo.formcheck.domain.model.PoseLandmarks

/** Converts an ML Kit [Pose] into our internal [PoseLandmarks] data class. */
object LandmarkMapper {

    fun fromPose(pose: Pose): PoseLandmarks? {
        val nose = pose.point(PoseLandmark.NOSE) ?: return null
        val leftEye = pose.point(PoseLandmark.LEFT_EYE) ?: nose
        val rightEye = pose.point(PoseLandmark.RIGHT_EYE) ?: nose
        val leftEar = pose.point(PoseLandmark.LEFT_EAR) ?: leftEye
        val rightEar = pose.point(PoseLandmark.RIGHT_EAR) ?: rightEye
        val leftShoulder = pose.point(PoseLandmark.LEFT_SHOULDER) ?: return null
        val rightShoulder = pose.point(PoseLandmark.RIGHT_SHOULDER) ?: return null
        val leftElbow = pose.point(PoseLandmark.LEFT_ELBOW) ?: return null
        val rightElbow = pose.point(PoseLandmark.RIGHT_ELBOW) ?: return null
        val leftWrist = pose.point(PoseLandmark.LEFT_WRIST) ?: return null
        val rightWrist = pose.point(PoseLandmark.RIGHT_WRIST) ?: return null
        val leftIndex = pose.point(PoseLandmark.LEFT_INDEX) ?: leftWrist
        val rightIndex = pose.point(PoseLandmark.RIGHT_INDEX) ?: rightWrist
        val leftHip = pose.point(PoseLandmark.LEFT_HIP) ?: return null
        val rightHip = pose.point(PoseLandmark.RIGHT_HIP) ?: return null
        val leftKnee = pose.point(PoseLandmark.LEFT_KNEE) ?: return null
        val rightKnee = pose.point(PoseLandmark.RIGHT_KNEE) ?: return null
        val leftAnkle = pose.point(PoseLandmark.LEFT_ANKLE) ?: return null
        val rightAnkle = pose.point(PoseLandmark.RIGHT_ANKLE) ?: return null

        // Forehead approximation: a bit above the eye line.
        val eyeMidY = (leftEye.y + rightEye.y) / 2f
        val foreheadY = eyeMidY - (nose.y - eyeMidY) * 1.5f

        val landmarks = listOf(
            leftShoulder, rightShoulder, leftElbow, rightElbow,
            leftWrist, rightWrist, leftHip, rightHip,
            leftKnee, rightKnee, leftAnkle, rightAnkle
        )
        val confidence = pose.allPoseLandmarks
            .filter { it.landmarkType in keyTypes }
            .map { it.inFrameLikelihood }
            .average()
            .toFloat()
            .takeIf { !it.isNaN() } ?: 0f

        return PoseLandmarks(
            nose = nose,
            leftEye = leftEye,
            rightEye = rightEye,
            leftEar = leftEar,
            rightEar = rightEar,
            leftShoulder = leftShoulder,
            rightShoulder = rightShoulder,
            leftElbow = leftElbow,
            rightElbow = rightElbow,
            leftWrist = leftWrist,
            rightWrist = rightWrist,
            leftIndex = leftIndex,
            rightIndex = rightIndex,
            leftHip = leftHip,
            rightHip = rightHip,
            leftKnee = leftKnee,
            rightKnee = rightKnee,
            leftAnkle = leftAnkle,
            rightAnkle = rightAnkle,
            foreheadY = foreheadY,
            confidence = confidence
        )
    }

    private val keyTypes = setOf(
        PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER,
        PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW,
        PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_WRIST,
        PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP,
        PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE
    )

    private fun Pose.point(type: Int): PointF? =
        getPoseLandmark(type)?.let { PointF(it.position.x, it.position.y) }
}
