package com.meuxo.formcheck.live

import com.meuxo.formcheck.dribble.DribbleAnalyzer
import com.meuxo.formcheck.tracking.Lm
import com.meuxo.formcheck.tracking.RimObs
import com.meuxo.formcheck.tracking.TrackedFrame
import com.meuxo.formcheck.tracking.TrackingConfig
import com.meuxo.formcheck.tracking.geom.Stats
import com.meuxo.formcheck.scoring.ScoringConfig
import com.meuxo.formcheck.domain.model.MetricId

/**
 * Live dribble feedback: 3 s standing calibration, then short spoken cues ("Get lower",
 * "Eyes up") from the last second of frames. The screen limits cues to one every few seconds.
 */
class DribbleLiveLogic : LiveLogic {
    private val calib = ArrayList<Pair<Double, Double>>()  // floor y, hip y
    private var floor: Double? = null
    private var hip: Double? = null
    private var t0 = -1L
    private val recentLow = ArrayDeque<Pair<Long, Double>>()
    private val recentPitch = ArrayDeque<Pair<Long, Double>>()

    override fun onFrame(frame: TrackedFrame, width: Int, height: Int, rim: RimObs?): LiveStatus {
        val p = frame.pose ?: return LiveStatus(listOf("Step into frame: whole body and feet."))
        if (t0 < 0) t0 = frame.timeUs
        val elapsed = (frame.timeUs - t0) / 1e6
        if (hip == null) {
            val feet = listOf(Lm.LEFT_HEEL, Lm.RIGHT_HEEL, Lm.LEFT_FOOT_INDEX, Lm.RIGHT_FOOT_INDEX).filter { p.visible(it) }.maxOfOrNull { p.px(it).y }
            if (feet != null && p.allVisible(Lm.LEFT_HIP, Lm.RIGHT_HIP)) calib += feet to p.hipMidPx().y
            if (elapsed < DribbleAnalyzer.CALIBRATION_SEC) return LiveStatus(listOf("Stand still: calibrating ${(DribbleAnalyzer.CALIBRATION_SEC - elapsed).toInt() + 1}…"))
            if (calib.size < 5) { t0 = frame.timeUs; calib.clear(); return LiveStatus(listOf("Keep your feet and hips in frame to calibrate.")) }
            floor = Stats.median(calib.map { it.first }); hip = floor!! - Stats.median(calib.map { it.second })
            return LiveStatus(listOf("Calibrated. Start the drill."), "Go")
        }
        val now = frame.timeUs
        if (p.allVisible(Lm.LEFT_HIP, Lm.RIGHT_HIP)) recentLow.addLast(now to 100 * (floor!! - p.hipMidPx().y) / hip!!)
        DribbleAnalyzer.headPitch(p)?.let { recentPitch.addLast(now to it) }
        while (recentLow.isNotEmpty() && now - recentLow.first().first > 1_000_000) recentLow.removeFirst()
        while (recentPitch.isNotEmpty() && now - recentPitch.first().first > 1_000_000) recentPitch.removeFirst()
        val low = recentLow.map { it.second }.takeIf { it.isNotEmpty() }?.average()
        val eyesUp = recentPitch.takeIf { it.isNotEmpty() }?.let { q -> 100.0 * q.count { it.second <= TrackingConfig.EYES_UP_MAX_PITCH_DEG } / q.size }
        val stanceMax = ScoringConfig.targets[MetricId.STANCE_LOWNESS]!!.high
        val cue = when {
            low != null && low > stanceMax + 5 -> "Get lower"
            eyesUp != null && eyesUp < 50 -> "Eyes up"
            else -> null
        }
        return LiveStatus(listOfNotNull(
            low?.let { "Hip height ${it.toInt()}% of standing" },
            eyesUp?.let { "Eyes up ${it.toInt()}% (last second)" }
        ), cue)
    }
}
