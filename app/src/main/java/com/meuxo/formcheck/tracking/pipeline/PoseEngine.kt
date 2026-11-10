package com.meuxo.formcheck.tracking.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import com.meuxo.formcheck.tracking.Lm
import com.meuxo.formcheck.tracking.PoseFrame
import com.meuxo.formcheck.tracking.geom.Vec2

/**
 * MediaPipe Pose Landmarker, heavy model, VIDEO running mode (temporal tracking), GPU
 * delegate with CPU fallback (spec 3a). Returns image landmarks in pixels of the input
 * bitmap and world landmarks in meters.
 */
class PoseEngine(
    private val context: Context,
    private val numPoses: Int = 1,
    private val runningMode: RunningMode = RunningMode.VIDEO
) : AutoCloseable {

    var delegate: Delegate = Delegate.GPU
        private set
    private var landmarker: PoseLandmarker = create(Delegate.GPU) ?: create(Delegate.CPU)
        ?: error("Could not load pose model ($MODEL_PATH)")
    private var lastTsMs = -1L

    private fun create(d: Delegate): PoseLandmarker? = try {
        val base = BaseOptions.builder().setModelAssetPath(MODEL_PATH).setDelegate(d).build()
        val opts = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(runningMode)
            .setNumPoses(numPoses)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build()
        PoseLandmarker.createFromOptions(context, opts).also { delegate = d }
    } catch (t: Throwable) {
        Log.w(TAG, "Pose landmarker with $d failed: ${t.message}")
        null
    }

    /** All poses on this frame. In VIDEO mode timestamps must increase; this enforces it. */
    fun detect(bitmap: Bitmap, timeUs: Long): List<PoseFrame> {
        val image = BitmapImageBuilder(bitmap).build()
        val result: PoseLandmarkerResult = try {
            if (runningMode == RunningMode.IMAGE) landmarker.detect(image) else {
                val ts = maxOf(timeUs / 1000, lastTsMs + 1).also { lastTsMs = it }
                landmarker.detectForVideo(image, ts)
            }
        } catch (t: Throwable) {
            if (delegate == Delegate.GPU) {
                // GPU can fail at inference time on some drivers: rebuild on CPU and retry once.
                Log.w(TAG, "GPU inference failed, switching to CPU: ${t.message}")
                landmarker.close()
                landmarker = create(Delegate.CPU) ?: throw t
                lastTsMs = -1
                return detect(bitmap, timeUs)
            }
            throw t
        }
        return toFrames(result, bitmap.width, bitmap.height)
    }

    override fun close() = landmarker.close()

    companion object {
        const val MODEL_PATH = "models/pose_landmarker_heavy.task"
        private const val TAG = "PoseEngine"

        fun toFrames(r: PoseLandmarkerResult, w: Int, h: Int): List<PoseFrame> {
            val out = ArrayList<PoseFrame>()
            for (k in r.landmarks().indices) {
                val lms = r.landmarks()[k]
                val world = r.worldLandmarks().getOrNull(k) ?: continue
                if (lms.size < Lm.COUNT || world.size < Lm.COUNT) continue
                val img = FloatArray(Lm.COUNT * 2)
                val wld = FloatArray(Lm.COUNT * 3)
                val vis = FloatArray(Lm.COUNT)
                for (i in 0 until Lm.COUNT) {
                    img[i * 2] = lms[i].x() * w
                    img[i * 2 + 1] = lms[i].y() * h
                    wld[i * 3] = world[i].x(); wld[i * 3 + 1] = world[i].y(); wld[i * 3 + 2] = world[i].z()
                    // No visibility reported means we cannot trust the point.
                    vis[i] = lms[i].visibility().orElse(0f)
                }
                out += PoseFrame(img, wld, vis)
            }
            return out
        }

        /**
         * Picks the tracked person among several (spec 3c): the pose whose hip midpoint is
         * nearest to [target] (pixels). With no target, the largest person.
         */
        fun pick(poses: List<PoseFrame>, target: Vec2?): PoseFrame? {
            if (poses.size <= 1) return poses.firstOrNull()
            if (target == null) return poses.maxByOrNull { bboxArea(it) }
            return poses.minByOrNull { it.hipMidPx().distanceTo(target) }
        }

        private fun bboxArea(p: PoseFrame): Double {
            var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
            for (i in 0 until Lm.COUNT) {
                val v = p.px(i); minX = minOf(minX, v.x); maxX = maxOf(maxX, v.x); minY = minOf(minY, v.y); maxY = maxOf(maxY, v.y)
            }
            return (maxX - minX) * (maxY - minY)
        }
    }
}
