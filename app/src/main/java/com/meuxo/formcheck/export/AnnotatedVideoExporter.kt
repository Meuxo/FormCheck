package com.meuxo.formcheck.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.CanvasOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.meuxo.formcheck.tracking.Lm
import com.meuxo.formcheck.tracking.TrackedClip
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Burns the skeleton and ball trail into a copy of the video with Media3 Transformer
 * (spec 5, optional export). Runs on device; the output stays in the session folder until
 * the user chooses to share it.
 */
@OptIn(UnstableApi::class)
class AnnotatedVideoExporter(private val context: Context) {

    suspend fun export(source: File, clip: TrackedClip, out: File): File = suspendCancellableCoroutine { cont ->
        val overlay = SkeletonCanvasOverlay(clip)
        val item = EditedMediaItem.Builder(MediaItem.fromUri(android.net.Uri.fromFile(source)))
            .setRemoveAudio(true)
            .setEffects(Effects(emptyList(), listOf(OverlayEffect(listOf(overlay)))))
            .build()
        Handler(Looper.getMainLooper()).post {
            val transformer = Transformer.Builder(context)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) { if (cont.isActive) cont.resume(out) }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                }).build()
            transformer.start(item, out.absolutePath)
            cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { transformer.cancel() } }
        }
    }

    private class SkeletonCanvasOverlay(private val clip: TrackedClip) : CanvasOverlay(true) {
        private val bone = Paint().apply { color = Color.WHITE; strokeWidth = 6f; isAntiAlias = true }
        private val ball = Paint().apply { color = 0xFFFF8F00.toInt(); style = Paint.Style.STROKE; strokeWidth = 6f; isAntiAlias = true }

        override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
            canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            if (clip.frames.isEmpty()) return
            val i = FrameLookup.indexForMediaTime(clip, presentationTimeUs)
            val f = clip.frames[i]
            val s = canvas.width.toFloat() / clip.width
            f.pose?.let { p ->
                for ((a, b) in Lm.BONES) {
                    if (!p.visible(a) || !p.visible(b)) continue
                    canvas.drawLine(p.img[a * 2] * s, p.img[a * 2 + 1] * s, p.img[b * 2] * s, p.img[b * 2 + 1] * s, bone)
                }
            }
            f.ball?.takeIf { !it.predicted }?.let { canvas.drawCircle(it.x * s, it.y * s, it.radius * s, ball) }
        }
    }
}

object FrameLookup {
    /** Frame whose media timestamp is closest to [mediaUs] (binary search). */
    fun indexForMediaTime(clip: TrackedClip, mediaUs: Long): Int {
        val n = clip.frames.size
        if (n == 0) return 0
        var lo = 0; var hi = n - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (clip.mediaTimeUs(mid) < mediaUs) lo = mid + 1 else hi = mid
        }
        return if (lo > 0 && kotlin.math.abs(clip.mediaTimeUs(lo - 1) - mediaUs) < kotlin.math.abs(clip.mediaTimeUs(lo) - mediaUs)) lo - 1 else lo
    }
}
