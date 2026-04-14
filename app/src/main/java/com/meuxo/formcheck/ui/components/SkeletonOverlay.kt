package com.meuxo.formcheck.ui.components

import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.meuxo.formcheck.domain.model.AnalysisResult
import com.meuxo.formcheck.domain.model.PoseLandmarks
import com.meuxo.formcheck.ui.theme.ScoreBad
import com.meuxo.formcheck.ui.theme.ScoreGood
import com.meuxo.formcheck.ui.theme.ScoreWarn

/**
 * Per-body-part colors used by [SkeletonOverlay]. Each segment is colored
 * based on the analysis score most relevant to it.
 *
 *  - rightArm: shooting arm — driven by elbow flare + elbow angle.
 *  - leftArm:  guide hand — driven by guide-hand passivity score.
 *  - torso:    base/balance — driven by the balance score.
 *  - legs:     loading — driven by knee-bend score.
 */
data class SegmentColors(
    val rightArm: Color,
    val leftArm: Color,
    val torso: Color,
    val rightLeg: Color,
    val leftLeg: Color,
    val joint: Color = Color.White
) {
    companion object {
        val NEUTRAL = SegmentColors(
            rightArm = ScoreGood,
            leftArm = ScoreGood,
            torso = ScoreGood,
            rightLeg = ScoreGood,
            leftLeg = ScoreGood
        )

        /** Derive a [SegmentColors] from the per-component scores in [result]. */
        fun fromResult(result: AnalysisResult): SegmentColors {
            val rightArmScore = (result.elbowFlareScore + result.elbowAngleScore) / 2
            return SegmentColors(
                rightArm = colorForScore(rightArmScore),
                leftArm = colorForScore(result.guideHandScore),
                torso = colorForScore(result.balanceScore),
                rightLeg = colorForScore(result.kneeBendScore),
                leftLeg = colorForScore(result.kneeBendScore)
            )
        }
    }
}

/** Map a 0..100 score to one of the three feedback colors. */
fun colorForScore(score: Int): Color = when {
    score >= 80 -> ScoreGood
    score >= 60 -> ScoreWarn
    else -> ScoreBad
}

/**
 * Draws the pose skeleton on top of the underlying media. Coordinates are
 * scaled from source-image space ([imageWidth] × [imageHeight]) into the
 * composable's draw space.
 *
 * Each body region (arms, torso, legs) is colored independently via
 * [segmentColors] so the user can immediately see which part of their form
 * was good (green), borderline (yellow) or problematic (red).
 */
@Composable
fun SkeletonOverlay(
    landmarks: PoseLandmarks?,
    imageWidth: Int,
    imageHeight: Int,
    segmentColors: SegmentColors = SegmentColors.NEUTRAL,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val lm = landmarks ?: return@Canvas
        if (imageWidth <= 0 || imageHeight <= 0) return@Canvas

        // Letter-box scaling so the overlay matches the displayed media,
        // regardless of which axis the parent container constrained.
        val scale = minOf(
            size.width / imageWidth.toFloat(),
            size.height / imageHeight.toFloat()
        )
        val drawnW = imageWidth * scale
        val drawnH = imageHeight * scale
        val offsetX = (size.width - drawnW) / 2f
        val offsetY = (size.height - drawnH) / 2f
        fun PointF.scaled() = Offset(x * scale + offsetX, y * scale + offsetY)

        val boneWidth = 5f
        val pointRadius = 2.dp.toPx()

        // ---- Right arm (shooting arm) ----
        drawLine(
            color = segmentColors.rightArm,
            start = lm.rightShoulder.scaled(),
            end = lm.rightElbow.scaled(),
            strokeWidth = boneWidth
        )
        drawLine(
            color = segmentColors.rightArm,
            start = lm.rightElbow.scaled(),
            end = lm.rightWrist.scaled(),
            strokeWidth = boneWidth
        )

        // ---- Left arm (guide hand) ----
        drawLine(
            color = segmentColors.leftArm,
            start = lm.leftShoulder.scaled(),
            end = lm.leftElbow.scaled(),
            strokeWidth = boneWidth
        )
        drawLine(
            color = segmentColors.leftArm,
            start = lm.leftElbow.scaled(),
            end = lm.leftWrist.scaled(),
            strokeWidth = boneWidth
        )

        // ---- Torso ----
        drawLine(
            color = segmentColors.torso,
            start = lm.leftShoulder.scaled(),
            end = lm.rightShoulder.scaled(),
            strokeWidth = boneWidth
        )
        drawLine(
            color = segmentColors.torso,
            start = lm.leftHip.scaled(),
            end = lm.rightHip.scaled(),
            strokeWidth = boneWidth
        )
        drawLine(
            color = segmentColors.torso,
            start = lm.leftShoulder.scaled(),
            end = lm.leftHip.scaled(),
            strokeWidth = boneWidth
        )
        drawLine(
            color = segmentColors.torso,
            start = lm.rightShoulder.scaled(),
            end = lm.rightHip.scaled(),
            strokeWidth = boneWidth
        )

        // ---- Legs ----
        drawLine(
            color = segmentColors.rightLeg,
            start = lm.rightHip.scaled(),
            end = lm.rightKnee.scaled(),
            strokeWidth = boneWidth
        )
        drawLine(
            color = segmentColors.rightLeg,
            start = lm.rightKnee.scaled(),
            end = lm.rightAnkle.scaled(),
            strokeWidth = boneWidth
        )
        drawLine(
            color = segmentColors.leftLeg,
            start = lm.leftHip.scaled(),
            end = lm.leftKnee.scaled(),
            strokeWidth = boneWidth
        )
        drawLine(
            color = segmentColors.leftLeg,
            start = lm.leftKnee.scaled(),
            end = lm.leftAnkle.scaled(),
            strokeWidth = boneWidth
        )

        // ---- Joints ----
        listOf(
            lm.leftShoulder, lm.rightShoulder, lm.leftElbow, lm.rightElbow,
            lm.leftWrist, lm.rightWrist, lm.leftHip, lm.rightHip,
            lm.leftKnee, lm.rightKnee, lm.leftAnkle, lm.rightAnkle
        ).forEach { p ->
            drawCircle(
                color = segmentColors.joint,
                radius = pointRadius,
                center = p.scaled()
            )
        }
    }
}
