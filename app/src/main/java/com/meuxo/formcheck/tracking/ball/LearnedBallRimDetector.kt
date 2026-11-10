package com.meuxo.formcheck.tracking.ball

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.meuxo.formcheck.tracking.BallSource
import com.meuxo.formcheck.tracking.RimObs
import com.meuxo.formcheck.tracking.RimSource
import com.meuxo.formcheck.tracking.geom.Vec2

/**
 * Learned detector through MediaPipe's Object Detector task (TFLite). See docs/MODELS.md.
 *
 * - If `assets/models/ball_rim.tflite` exists (a custom model with `ball` and `rim` labels,
 *   e.g. trained with MediaPipe Model Maker), it is used for both.
 * - Otherwise the bundled EfficientDet-Lite0 COCO model is used for the ball only, through
 *   its "sports ball" class. COCO has no rim class, so the rim then comes from the user's
 *   tap or the classical detector.
 *
 * The detector needs an android Bitmap; [detectBall] converts the [RgbImage] once.
 */
class LearnedBallRimDetector private constructor(
    private val detector: ObjectDetector,
    private val hasRimClass: Boolean
) : BallRimDetector {

    override fun detectBall(frame: RgbImage, timeUs: Long, hint: Vec2?): List<BallCandidate> =
        detectBallBitmap(Bitmap.createBitmap(frame.pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888))

    fun detectBallBitmap(bmp: Bitmap): List<BallCandidate> {
        val res = detector.detect(BitmapImageBuilder(bmp).build())
        return res.detections().mapNotNull { d ->
            val cat = d.categories().maxByOrNull { it.score() } ?: return@mapNotNull null
            if (cat.categoryName().lowercase() !in BALL_LABELS) return@mapNotNull null
            val b = d.boundingBox()
            BallCandidate(b.centerX().toDouble(), b.centerY().toDouble(), (b.width() + b.height()) / 4.0,
                cat.score().toDouble(), BallSource.LEARNED)
        }.sortedByDescending { it.score }
    }

    override fun detectRim(frame: RgbImage): RimObs? {
        if (!hasRimClass) return null
        val bmp = Bitmap.createBitmap(frame.pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888)
        val res = detector.detect(BitmapImageBuilder(bmp).build())
        return res.detections().mapNotNull { d ->
            val cat = d.categories().maxByOrNull { it.score() } ?: return@mapNotNull null
            if (cat.categoryName().lowercase() !in RIM_LABELS) return@mapNotNull null
            val b = d.boundingBox()
            RimObs(b.centerX(), b.centerY(), b.width(), cat.score(), RimSource.LEARNED)
        }.maxByOrNull { it.confidence }
    }

    override fun close() = detector.close()

    companion object {
        const val CUSTOM_MODEL = "models/ball_rim.tflite"
        const val COCO_MODEL = "models/efficientdet_lite0.tflite"
        private val BALL_LABELS = setOf("sports ball", "ball", "basketball")
        private val RIM_LABELS = setOf("rim", "hoop", "basketball hoop")

        /** Null if no model asset can be loaded (the caller then uses the classical detector). */
        fun create(context: Context): LearnedBallRimDetector? {
            val assets = context.assets.list("models").orEmpty().toSet()
            val custom = "ball_rim.tflite" in assets
            val path = if (custom) CUSTOM_MODEL else if ("efficientdet_lite0.tflite" in assets) COCO_MODEL else return null
            return try {
                val opts = ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(path).build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setScoreThreshold(0.15f)
                    .setMaxResults(5)
                    .build()
                LearnedBallRimDetector(ObjectDetector.createFromOptions(context, opts), hasRimClass = custom)
            } catch (t: Throwable) {
                Log.w("LearnedBallRim", "Model unavailable: ${t.message}")
                null
            }
        }
    }
}
