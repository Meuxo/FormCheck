package com.meuxo.formcheck.data.storage

import com.meuxo.formcheck.tracking.BallObs
import com.meuxo.formcheck.tracking.BallSource
import com.meuxo.formcheck.tracking.Lm
import com.meuxo.formcheck.tracking.PoseFrame
import com.meuxo.formcheck.tracking.RimObs
import com.meuxo.formcheck.tracking.RimSource
import com.meuxo.formcheck.tracking.TrackedClip
import com.meuxo.formcheck.tracking.TrackedFrame
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.roundToInt

/**
 * Compact gzipped binary for per-frame landmarks and ball positions (spec 5).
 *
 * Layout (big-endian), version 1:
 *   magic "FCT1", int version, int width, int height, int frameCount,
 *   byte hasRim [float x, y, width, confidence, byte source], int rimMovedAt (-1 = none),
 *   double timeScale (real seconds per media second)
 *   per frame: long timeUs, byte flags (1 = pose, 2 = ball, 4 = ball predicted)
 *     pose: 33 × (float x, float y) image px; 33 × 3 short world mm; 33 × byte visibility×255
 *     ball: float x, y, radius; byte confidence×255; byte source
 *
 * Image coordinates stay full float so the overlay is pixel exact; world coordinates are
 * quantised to 1 mm, well below the pose model's error.
 */
object TrackCodec {
    private const val MAGIC = 0x46435431 // "FCT1"
    private const val VERSION = 1

    fun write(clip: TrackedClip, out: OutputStream) {
        DataOutputStream(GZIPOutputStream(out.buffered())).use { d ->
            d.writeInt(MAGIC); d.writeInt(VERSION)
            d.writeInt(clip.width); d.writeInt(clip.height); d.writeInt(clip.frames.size)
            val rim = clip.rim
            d.writeByte(if (rim != null) 1 else 0)
            if (rim != null) {
                d.writeFloat(rim.x); d.writeFloat(rim.y); d.writeFloat(rim.width); d.writeFloat(rim.confidence)
                d.writeByte(rim.source.ordinal)
            }
            d.writeInt(clip.rimMovedAtFrame ?: -1)
            d.writeDouble(clip.timeScale)
            for (f in clip.frames) {
                d.writeLong(f.timeUs)
                val p = f.pose; val b = f.ball
                var flags = 0
                if (p != null) flags = flags or 1
                if (b != null) flags = flags or 2
                if (b?.predicted == true) flags = flags or 4
                d.writeByte(flags)
                if (p != null) {
                    for (v in p.img) d.writeFloat(v)
                    for (v in p.world) d.writeShort((v * 1000f).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()))
                    for (v in p.vis) d.writeByte((v.coerceIn(0f, 1f) * 255f).roundToInt())
                }
                if (b != null) {
                    d.writeFloat(b.x); d.writeFloat(b.y); d.writeFloat(b.radius)
                    d.writeByte((b.confidence.coerceIn(0f, 1f) * 255f).roundToInt())
                    d.writeByte(b.source.ordinal)
                }
            }
        }
    }

    fun read(input: InputStream): TrackedClip {
        DataInputStream(GZIPInputStream(input.buffered())).use { d ->
            require(d.readInt() == MAGIC) { "Not a FormCheck track file" }
            val version = d.readInt()
            require(version == VERSION) { "Unsupported track version $version" }
            val w = d.readInt(); val h = d.readInt(); val n = d.readInt()
            val rim = if (d.readByte().toInt() == 1) RimObs(
                d.readFloat(), d.readFloat(), d.readFloat(), d.readFloat(), RimSource.entries[d.readByte().toInt()]
            ) else null
            val moved = d.readInt().takeIf { it >= 0 }
            val timeScale = d.readDouble().takeIf { !it.isNaN() && it > 0 } ?: 1.0
            val frames = ArrayList<TrackedFrame>(n)
            try {
                for (i in 0 until n) {
                    val t = d.readLong()
                    val flags = d.readUnsignedByte()
                    val pose = if (flags and 1 != 0) {
                        val img = FloatArray(Lm.COUNT * 2) { d.readFloat() }
                        val world = FloatArray(Lm.COUNT * 3) { d.readShort() / 1000f }
                        val vis = FloatArray(Lm.COUNT) { d.readUnsignedByte() / 255f }
                        PoseFrame(img, world, vis)
                    } else null
                    val ball = if (flags and 2 != 0) {
                        val x = d.readFloat(); val y = d.readFloat(); val r = d.readFloat()
                        val c = d.readUnsignedByte() / 255f
                        val src = BallSource.entries[d.readUnsignedByte()]
                        BallObs(x, y, r, c, src, predicted = flags and 4 != 0)
                    } else null
                    frames += TrackedFrame(i, t, pose, ball)
                }
            } catch (e: EOFException) {
                throw IllegalStateException("Track file truncated after ${frames.size} of $n frames", e)
            }
            return TrackedClip(w, h, frames, rim, moved, timeScale)
        }
    }
}
