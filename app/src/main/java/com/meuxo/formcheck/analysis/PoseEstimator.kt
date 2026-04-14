package com.meuxo.formcheck.analysis

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.net.toUri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import com.meuxo.formcheck.domain.model.PoseLandmarks
import com.meuxo.formcheck.util.LandmarkMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Wraps ML Kit pose detection. Supports both single images and video frame
 * extraction at ~10 fps via MediaMetadataRetriever.
 */
@Singleton
class PoseEstimator @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) {

    /**
     * Result of running pose detection over a media URI.
     *
     * [frames] holds one [PoseLandmarks] per sampled frame (or one entry for
     * still images). [width]/[height] are the source pixel dimensions
     * post-rotation, so they line up 1:1 with the landmark coordinates and
     * with what ExoPlayer/Coil will display.
     */
    data class Output(
        val frames: List<PoseLandmarks>,
        val width: Int,
        val height: Int,
        val isVideo: Boolean,
        val frameIntervalMs: Long
    ) {
        companion object {
            val EMPTY = Output(emptyList(), 0, 0, false, 100L)
        }
    }

    private val detector: PoseDetector by lazy {
        val opts = AccuratePoseDetectorOptions.Builder()
            .setDetectorMode(AccuratePoseDetectorOptions.SINGLE_IMAGE_MODE)
            .build()
        PoseDetection.getClient(opts)
    }

    /** Detect from a string URI which may be a video or image. */
    suspend fun detectFromUri(uriString: String): Output =
        withContext(Dispatchers.Default) {
            val uri = uriString.toUri()
            val mime = context.contentResolver.getType(uri).orEmpty()
            if (mime.startsWith("video")) {
                detectVideo(uri)
            } else {
                detectImage(uri)
            }
        }

    private suspend fun detectImage(uri: Uri): Output {
        val bmp = loadBitmap(uri) ?: return Output.EMPTY
        val w = bmp.width
        val h = bmp.height
        val landmarks = runDetection(InputImage.fromBitmap(bmp, 0))
        if (!bmp.isRecycled) bmp.recycle()
        return Output(
            frames = listOfNotNull(landmarks),
            width = w,
            height = h,
            isVideo = false,
            frameIntervalMs = 0L
        )
    }

    private suspend fun detectVideo(uri: Uri): Output {
        val frameIntervalMs = 100L // 10 fps
        val frames = extractFrames(uri, frameIntervalMs)
        if (frames.isEmpty()) return Output.EMPTY
        val width = frames.first().width
        val height = frames.first().height
        val landmarks = ArrayList<PoseLandmarks>(frames.size)
        for (bmp in frames) {
            try {
                val lm = runDetection(InputImage.fromBitmap(bmp, 0))
                if (lm != null) landmarks.add(lm)
            } finally {
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
        return Output(
            frames = landmarks,
            width = width,
            height = height,
            isVideo = true,
            frameIntervalMs = frameIntervalMs
        )
    }

    private fun loadBitmap(uri: Uri): Bitmap? {
        return context.contentResolver.openInputStream(uri)?.use { input ->
            android.graphics.BitmapFactory.decodeStream(input)
        }
    }

    private fun extractFrames(uri: Uri, stepMs: Long): List<Bitmap> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val frames = mutableListOf<Bitmap>()
            var t = 0L
            while (t <= durationMs) {
                val bmp = retriever.getFrameAtTime(
                    t * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST
                )
                if (bmp != null) frames.add(bmp)
                t += stepMs
            }
            frames
        } catch (t: Throwable) {
            emptyList()
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) { }
        }
    }

    private suspend fun runDetection(image: InputImage): PoseLandmarks? =
        suspendCancellableCoroutine { cont ->
            detector.process(image)
                .addOnSuccessListener { pose ->
                    cont.resume(LandmarkMapper.fromPose(pose))
                }
                .addOnFailureListener { cont.resume(null) }
        }
}
