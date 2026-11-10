package com.meuxo.formcheck.tracking.ball

import com.meuxo.formcheck.tracking.BallSource
import com.meuxo.formcheck.tracking.RimObs
import com.meuxo.formcheck.tracking.geom.Vec2

/** ARGB pixel buffer. Plain Kotlin so detectors run in unit tests. */
class RgbImage(val width: Int, val height: Int, val pixels: IntArray) {
    init { require(pixels.size == width * height) }
    fun argb(x: Int, y: Int) = pixels[y * width + x]
}

data class BallCandidate(
    val x: Double,
    val y: Double,
    val radius: Double,
    /** 0..1 */
    val score: Double,
    val source: BallSource
) {
    val center get() = Vec2(x, y)
}

/**
 * Ball and rim detection (spec 3d). Implementations:
 *  - [LearnedBallRimDetector]: TFLite object detector through MediaPipe Tasks.
 *  - [ClassicalBallRimDetector]: colour segmentation + circle fit + motion gating. No ML.
 *  - [FallbackBallRimDetector]: tries learned first, falls back to classical when the model
 *    is missing or its best score is low.
 *
 * Coordinates are pixels of the [RgbImage] passed in.
 */
interface BallRimDetector : AutoCloseable {
    /** Candidate balls on this frame, best first. [hint] is the tracker's predicted position. */
    fun detectBall(frame: RgbImage, timeUs: Long, hint: Vec2?): List<BallCandidate>

    /** Rim on this frame, or null. Most implementations are only reliable on a static camera. */
    fun detectRim(frame: RgbImage): RimObs?

    override fun close() {}
}

class FallbackBallRimDetector(
    private val learned: BallRimDetector?,
    private val classical: BallRimDetector,
    private val minLearnedScore: Double
) : BallRimDetector {

    override fun detectBall(frame: RgbImage, timeUs: Long, hint: Vec2?): List<BallCandidate> {
        val fromModel = try { learned?.detectBall(frame, timeUs, hint).orEmpty() } catch (_: Throwable) { emptyList() }
        // The classical detector keeps its motion history up to date only if it sees every frame.
        val fromClassical = classical.detectBall(frame, timeUs, hint)
        val bestModel = fromModel.maxOfOrNull { it.score } ?: 0.0
        return if (bestModel >= minLearnedScore) fromModel.sortedByDescending { it.score } else
            (fromModel + fromClassical).sortedByDescending { it.score }
    }

    override fun detectRim(frame: RgbImage): RimObs? =
        (try { learned?.detectRim(frame) } catch (_: Throwable) { null }) ?: classical.detectRim(frame)

    override fun close() {
        learned?.close(); classical.close()
    }
}
