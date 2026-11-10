package com.meuxo.formcheck.tracking.pipeline

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Decodes every frame of a video at its native frame rate with MediaExtractor + MediaCodec,
 * using the container's real presentation timestamps (spec 3a). Frames are delivered one
 * at a time and never all held in memory.
 *
 * Output bitmaps are upright (container rotation applied) and downscaled so the long side
 * is at most [maxLongSide].
 */
class VideoFrameDecoder {

    data class Info(
        val width: Int,
        val height: Int,
        val rotation: Int,
        val durationUs: Long,
        val declaredFps: Double?,
        /** Capture rate of slow-motion files; timestamps are rescaled to real time when set. */
        val captureFps: Double?
    )

    fun probe(file: File): Info {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(file.absolutePath)
            val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val dur = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
            val capture = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull()
            val ex = MediaExtractor()
            var fps: Double? = null
            try {
                ex.setDataSource(file.absolutePath)
                val track = videoTrack(ex)
                if (track >= 0) {
                    val f = ex.getTrackFormat(track)
                    if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) fps = f.getNumber(MediaFormat.KEY_FRAME_RATE)?.toDouble()
                }
            } finally { ex.release() }
            return Info(w, h, rot, dur, fps, capture?.takeIf { c -> fps != null && c > fps * 1.5 })
        } finally {
            mmr.release()
        }
    }

    /**
     * Decodes [file] and calls [onFrame] for every frame with its index and real timestamp
     * (µs). The bitmap is only valid during the callback.
     */
    suspend fun decode(
        file: File,
        maxLongSide: Int,
        onFrame: suspend (index: Int, timeUs: Long, bitmap: Bitmap) -> Unit
    ): Info {
        val info = probe(file)
        val timeScale = info.captureFps?.let { cap -> (info.declaredFps ?: cap) / cap } ?: 1.0
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        val track = videoTrack(extractor)
        require(track >= 0) { "No video track in file" }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()
        val bufInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var index = 0
        var argb: IntArray? = null
        try {
            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(bufInfo, 10_000)
                if (outIdx >= 0) {
                    if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    if (bufInfo.size > 0) {
                        val image = codec.getOutputImage(outIdx)
                        if (image != null) {
                            val (bmp, buffer) = toBitmap(image, info.rotation, maxLongSide, argb)
                            argb = buffer
                            image.close()
                            codec.releaseOutputBuffer(outIdx, false)
                            onFrame(index++, (bufInfo.presentationTimeUs * timeScale).toLong(), bmp)
                            bmp.recycle()
                            continue
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                }
            }
        } finally {
            try { codec.stop() } catch (_: Throwable) {}
            codec.release()
            extractor.release()
        }
        return info
    }

    private fun videoTrack(ex: MediaExtractor): Int {
        for (i in 0 until ex.trackCount) {
            if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) return i
        }
        return -1
    }

    /** YUV_420_888 → upright, downscaled ARGB bitmap (BT.601 limited range, nearest neighbour). */
    private fun toBitmap(image: Image, rotation: Int, maxLongSide: Int, reuse: IntArray?): Pair<Bitmap, IntArray> {
        val crop = image.cropRect
        val srcW = crop.width()
        val srcH = crop.height()
        val scale = minOf(1.0, maxLongSide.toDouble() / max(srcW, srcH))
        val sw = max(1, (srcW * scale).roundToInt())
        val sh = max(1, (srcH * scale).roundToInt())
        val rotated = rotation == 90 || rotation == 270
        val outW = if (rotated) sh else sw
        val outH = if (rotated) sw else sh
        val out = if (reuse != null && reuse.size == outW * outH) reuse else IntArray(outW * outH)

        val yPlane = image.planes[0]; val uPlane = image.planes[1]; val vPlane = image.planes[2]
        val yBuf = yPlane.buffer; val uBuf = uPlane.buffer; val vBuf = vPlane.buffer
        val yRow = yPlane.rowStride; val yPix = yPlane.pixelStride
        val uRow = uPlane.rowStride; val uPix = uPlane.pixelStride
        val vRow = vPlane.rowStride; val vPix = vPlane.pixelStride
        val inv = 1.0 / scale
        for (oy in 0 until sh) {
            val sy = (crop.top + (oy * inv).toInt()).coerceAtMost(crop.bottom - 1)
            for (ox in 0 until sw) {
                val sx = (crop.left + (ox * inv).toInt()).coerceAtMost(crop.right - 1)
                val y = (yBuf.get(sy * yRow + sx * yPix).toInt() and 0xFF) - 16
                val u = (uBuf.get((sy / 2) * uRow + (sx / 2) * uPix).toInt() and 0xFF) - 128
                val v = (vBuf.get((sy / 2) * vRow + (sx / 2) * vPix).toInt() and 0xFF) - 128
                val yy = 1192 * y.coerceAtLeast(0)
                val r = ((yy + 1634 * v) shr 10).coerceIn(0, 255)
                val g = ((yy - 833 * v - 400 * u) shr 10).coerceIn(0, 255)
                val b = ((yy + 2066 * u) shr 10).coerceIn(0, 255)
                val (dx, dy) = when (rotation) {
                    90 -> (sh - 1 - oy) to ox
                    180 -> (sw - 1 - ox) to (sh - 1 - oy)
                    270 -> oy to (sw - 1 - ox)
                    else -> ox to oy
                }
                out[dy * outW + dx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val bmp = Bitmap.createBitmap(out, outW, outH, Bitmap.Config.ARGB_8888)
        return bmp to out
    }
}
