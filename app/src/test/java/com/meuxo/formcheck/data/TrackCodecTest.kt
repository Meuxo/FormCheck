package com.meuxo.formcheck.data

import com.meuxo.formcheck.data.storage.SessionFiles
import com.meuxo.formcheck.data.storage.TrackCodec
import com.meuxo.formcheck.domain.model.Metric
import com.meuxo.formcheck.domain.model.MetricId
import com.meuxo.formcheck.domain.model.SessionResult
import com.meuxo.formcheck.domain.model.SessionType
import com.meuxo.formcheck.scoring.OverallScore
import com.meuxo.formcheck.testutil.Synthetic
import com.meuxo.formcheck.tracking.BallObs
import com.meuxo.formcheck.tracking.BallSource
import com.meuxo.formcheck.tracking.CameraView
import com.meuxo.formcheck.tracking.Lm
import com.meuxo.formcheck.tracking.RimObs
import com.meuxo.formcheck.tracking.RimSource
import com.meuxo.formcheck.tracking.TrackedClip
import com.meuxo.formcheck.tracking.TrackedFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class TrackCodecTest {

    @Test fun `round trip keeps every frame, exact image coords and mm world coords`() {
        val frames = (0 until 50).map { i ->
            val pose = if (i % 7 == 3) null else Synthetic.pose(Synthetic.Posture(kneeDeg = 120.0 + i))
            val ball = if (i % 5 == 0) null else BallObs(10f + i, 20f, 11.5f, 0.8f, BallSource.CLASSICAL, predicted = i % 9 == 0)
            TrackedFrame(i, i * 16_667L, pose, ball)
        }
        val clip = TrackedClip(960, 540, frames, RimObs(400f, 120f, 60f, 1f, RimSource.USER_TAP), rimMovedAtFrame = 33, timeScale = 0.125)
        val bytes = ByteArrayOutputStream().also { TrackCodec.write(clip, it) }.toByteArray()
        val back = TrackCodec.read(ByteArrayInputStream(bytes))

        assertEquals(960, back.width); assertEquals(540, back.height)
        assertEquals(50, back.frames.size)
        assertEquals(33, back.rimMovedAtFrame)
        assertEquals(0.125, back.timeScale, 0.0)
        assertEquals(RimSource.USER_TAP, back.rim!!.source)
        for (i in frames.indices) {
            val a = frames[i]; val b = back.frames[i]
            assertEquals(a.timeUs, b.timeUs)
            assertEquals(a.pose == null, b.pose == null)
            assertEquals(a.ball == null, b.ball == null)
            a.pose?.let { p ->
                for (k in p.img.indices) assertEquals(p.img[k], b.pose!!.img[k], 0f)
                for (k in p.world.indices) assertEquals(p.world[k], b.pose!!.world[k], 0.0006f)
                for (k in 0 until Lm.COUNT) assertEquals(p.vis[k], b.pose!!.vis[k], 1 / 255f)
            }
            a.ball?.let { assertEquals(it.predicted, b.ball!!.predicted); assertEquals(it.x, b.ball!!.x, 0f) }
        }
        // 50 frames of full pose data compress to a few KB.
        assertTrue("size ${bytes.size}", bytes.size < 50 * 600)
    }

    @Test fun `result json round trip keeps not-measured metrics`() {
        val r = SessionResult(
            SessionType.FORM, 1L, 60.0, 960, 540, 100, CameraView.SIDE, 0.9, null, listOf("w"),
            listOf(Metric.notMeasured(MetricId.ENTRY_ANGLE, "Rim not located")), emptyList(),
            OverallScore(null, 0, 1), null, null
        )
        val s = SessionFiles.json.encodeToString(SessionResult.serializer(), r)
        val back = SessionFiles.json.decodeFromString(SessionResult.serializer(), s)
        assertNull(back.metrics[0].value)
        assertEquals("Rim not located", back.metrics[0].notMeasuredReason)
    }
}
