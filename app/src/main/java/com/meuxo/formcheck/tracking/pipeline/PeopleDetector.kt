package com.meuxo.formcheck.tracking.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.meuxo.formcheck.tracking.Lm
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/** A person on the preview frame, normalised 0..1 coordinates. */
data class PersonBox(val left: Double, val top: Double, val right: Double, val bottom: Double, val hipX: Double, val hipY: Double)

/** Finds everyone on an early frame so the user can tap the shooter (spec 3c). */
class PeopleDetector @Inject constructor(@ApplicationContext private val context: Context) {

    fun detect(video: File): List<PersonBox> {
        val bmp = previewFrame(video) ?: return emptyList()
        return try {
            PoseEngine(context, numPoses = 4, runningMode = RunningMode.IMAGE).use { engine ->
                engine.detect(bmp, 0).map { p ->
                    var l = 1.0; var t = 1.0; var r = 0.0; var b = 0.0
                    for (i in 0 until Lm.COUNT) {
                        if (!p.visible(i, 0.3f)) continue
                        val v = p.px(i)
                        l = minOf(l, v.x / bmp.width); r = maxOf(r, v.x / bmp.width)
                        t = minOf(t, v.y / bmp.height); b = maxOf(b, v.y / bmp.height)
                    }
                    val hip = p.hipMidPx()
                    PersonBox(l, t, r, b, hip.x / bmp.width, hip.y / bmp.height)
                }
            }
        } catch (_: Throwable) { emptyList() } finally { bmp.recycle() }
    }

    companion object {
        /** Frame 0.5 s in, upright, long side ≤ 960 px. Used for person picking and calibration. */
        fun previewFrame(f: File, timeUs: Long = 500_000): Bitmap? = runCatching {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(f.absolutePath)
                val b = mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
                val s = 960.0 / maxOf(b.width, b.height)
                if (s < 1) Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true).also { b.recycle() } else b
            } finally { mmr.release() }
        }.getOrNull()
    }
}
