package com.meuxo.formcheck.tracking

import com.meuxo.formcheck.tracking.geom.Vec2
import com.meuxo.formcheck.tracking.geom.Vec3
import kotlinx.serialization.Serializable

/** MediaPipe Pose Landmarker's 33-point topology (person's left/right, not image left/right). */
object Lm {
    const val COUNT = 33
    const val NOSE = 0
    const val LEFT_EYE = 2
    const val RIGHT_EYE = 5
    const val LEFT_EAR = 7
    const val RIGHT_EAR = 8
    const val LEFT_SHOULDER = 11
    const val RIGHT_SHOULDER = 12
    const val LEFT_ELBOW = 13
    const val RIGHT_ELBOW = 14
    const val LEFT_WRIST = 15
    const val RIGHT_WRIST = 16
    const val LEFT_INDEX = 19
    const val RIGHT_INDEX = 20
    const val LEFT_HIP = 23
    const val RIGHT_HIP = 24
    const val LEFT_KNEE = 25
    const val RIGHT_KNEE = 26
    const val LEFT_ANKLE = 27
    const val RIGHT_ANKLE = 28
    const val LEFT_HEEL = 29
    const val RIGHT_HEEL = 30
    const val LEFT_FOOT_INDEX = 31
    const val RIGHT_FOOT_INDEX = 32

    /** Bones drawn by the overlay. */
    val BONES: List<Pair<Int, Int>> = listOf(
        LEFT_SHOULDER to RIGHT_SHOULDER, LEFT_HIP to RIGHT_HIP,
        LEFT_SHOULDER to LEFT_HIP, RIGHT_SHOULDER to RIGHT_HIP,
        LEFT_SHOULDER to LEFT_ELBOW, LEFT_ELBOW to LEFT_WRIST, LEFT_WRIST to LEFT_INDEX,
        RIGHT_SHOULDER to RIGHT_ELBOW, RIGHT_ELBOW to RIGHT_WRIST, RIGHT_WRIST to RIGHT_INDEX,
        LEFT_HIP to LEFT_KNEE, LEFT_KNEE to LEFT_ANKLE, LEFT_ANKLE to LEFT_HEEL, LEFT_HEEL to LEFT_FOOT_INDEX,
        RIGHT_HIP to RIGHT_KNEE, RIGHT_KNEE to RIGHT_ANKLE, RIGHT_ANKLE to RIGHT_HEEL, RIGHT_HEEL to RIGHT_FOOT_INDEX,
        NOSE to LEFT_EYE, NOSE to RIGHT_EYE
    )
}

@Serializable
enum class Side {
    LEFT, RIGHT;

    val other: Side get() = if (this == LEFT) RIGHT else LEFT
    val shoulder get() = if (this == LEFT) Lm.LEFT_SHOULDER else Lm.RIGHT_SHOULDER
    val elbow get() = if (this == LEFT) Lm.LEFT_ELBOW else Lm.RIGHT_ELBOW
    val wrist get() = if (this == LEFT) Lm.LEFT_WRIST else Lm.RIGHT_WRIST
    val index get() = if (this == LEFT) Lm.LEFT_INDEX else Lm.RIGHT_INDEX
    val hip get() = if (this == LEFT) Lm.LEFT_HIP else Lm.RIGHT_HIP
    val knee get() = if (this == LEFT) Lm.LEFT_KNEE else Lm.RIGHT_KNEE
    val ankle get() = if (this == LEFT) Lm.LEFT_ANKLE else Lm.RIGHT_ANKLE
    val heel get() = if (this == LEFT) Lm.LEFT_HEEL else Lm.RIGHT_HEEL
    val toe get() = if (this == LEFT) Lm.LEFT_FOOT_INDEX else Lm.RIGHT_FOOT_INDEX
    val ear get() = if (this == LEFT) Lm.LEFT_EAR else Lm.RIGHT_EAR

    fun label() = if (this == LEFT) "Left" else "Right"
}

/**
 * One person's pose on one frame.
 *
 * - [img]: image landmarks in PIXELS of the analyzed frame (x right, y down), interleaved x,y.
 * - [world]: MediaPipe world landmarks in METERS (hip-centred), interleaved x,y,z.
 * - [vis]: per-landmark visibility (0..1) as reported by the model.
 *
 * Arrays keep the per-frame memory small for long sessions.
 */
class PoseFrame(
    val img: FloatArray,
    val world: FloatArray,
    val vis: FloatArray
) {
    init {
        require(img.size == Lm.COUNT * 2 && world.size == Lm.COUNT * 3 && vis.size == Lm.COUNT)
    }

    fun px(i: Int) = Vec2(img[i * 2].toDouble(), img[i * 2 + 1].toDouble())
    fun w(i: Int) = Vec3(world[i * 3].toDouble(), world[i * 3 + 1].toDouble(), world[i * 3 + 2].toDouble())
    fun visible(i: Int, threshold: Float = TrackingConfig.VISIBILITY_THRESHOLD) = vis[i] >= threshold
    fun allVisible(vararg idx: Int, threshold: Float = TrackingConfig.VISIBILITY_THRESHOLD) =
        idx.all { vis[it] >= threshold }

    fun minVis(vararg idx: Int): Float = idx.minOf { vis[it] }

    fun hipMidPx() = Vec2.mid(px(Lm.LEFT_HIP), px(Lm.RIGHT_HIP))
    fun shoulderMidPx() = Vec2.mid(px(Lm.LEFT_SHOULDER), px(Lm.RIGHT_SHOULDER))
    fun ankleMidPx() = Vec2.mid(px(Lm.LEFT_ANKLE), px(Lm.RIGHT_ANKLE))

    fun copy(img: FloatArray = this.img.copyOf(), world: FloatArray = this.world.copyOf(), vis: FloatArray = this.vis.copyOf()) =
        PoseFrame(img, world, vis)

    companion object {
        fun empty() = PoseFrame(FloatArray(Lm.COUNT * 2), FloatArray(Lm.COUNT * 3), FloatArray(Lm.COUNT))
    }
}

/** A detected ball, in pixels of the analyzed frame. */
@Serializable
data class BallObs(
    val x: Float,
    val y: Float,
    val radius: Float,
    val confidence: Float,
    val source: BallSource,
    /** True if this position came from the Kalman prediction rather than a detection. */
    val predicted: Boolean = false
) {
    val center get() = Vec2(x.toDouble(), y.toDouble())
}

@Serializable
enum class BallSource { LEARNED, CLASSICAL, PREDICTED }

/** The rim, in pixels: centre of the rim opening and its horizontal width on screen. */
@Serializable
data class RimObs(
    val x: Float,
    val y: Float,
    val width: Float,
    val confidence: Float,
    val source: RimSource
) {
    val center get() = Vec2(x.toDouble(), y.toDouble())
}

@Serializable
enum class RimSource { USER_TAP, CLASSICAL, LEARNED }

/** Everything tracked on one video frame. Frames are never dropped: [pose] is null when absent. */
class TrackedFrame(
    val index: Int,
    /** Presentation timestamp from the container, microseconds, real time. */
    val timeUs: Long,
    val pose: PoseFrame?,
    val ball: BallObs?
) {
    val timeSec get() = timeUs / 1_000_000.0
}

/** A fully tracked clip. Width/height are the analyzed frame size (after rotation). */
class TrackedClip(
    val width: Int,
    val height: Int,
    val frames: List<TrackedFrame>,
    /** Rim per frame index where it was (re)located; empty if never located. */
    val rim: RimObs?,
    /** Frame index at which the rim moved (camera moved), or null. */
    val rimMovedAtFrame: Int? = null,
    /**
     * Real seconds per media second. 1.0 for normal video; below 1 for slow-motion files whose
     * container plays back slower than real time. Frame times are always real time; playback
     * divides by this to get the media position.
     */
    val timeScale: Double = 1.0
) {
    fun mediaTimeUs(frame: Int): Long = ((frames[frame].timeUs - frames[0].timeUs) / timeScale).toLong()

    /** Median frame rate derived from real timestamps. */
    val fps: Double by lazy {
        if (frames.size < 2) 0.0 else {
            val dts = (1 until frames.size).map { (frames[it].timeUs - frames[it - 1].timeUs).toDouble() }
                .filter { it > 0 }
            if (dts.isEmpty()) 0.0 else 1_000_000.0 / com.meuxo.formcheck.tracking.geom.Stats.median(dts)
        }
    }

    fun msBetween(a: Int, b: Int): Double = (frames[b].timeUs - frames[a].timeUs) / 1000.0
}
