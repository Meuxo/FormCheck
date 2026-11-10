package com.meuxo.formcheck.live

import com.meuxo.formcheck.domain.model.ShotOutcome
import com.meuxo.formcheck.shootaround.ShootaroundAnalyzer
import com.meuxo.formcheck.tracking.RimObs
import com.meuxo.formcheck.tracking.TrackedFrame

/**
 * Live shot counter: keeps the last few seconds of tracked frames and re-runs the same attempt
 * detector used offline twice a second. Counts are a preview; the recording is re-analysed.
 */
class ShootaroundLiveLogic : LiveLogic {
    private val buffer = ArrayDeque<TrackedFrame>()
    private val counted = HashMap<Long, ShotOutcome>()   // release time (µs) → outcome
    private var lastRunUs = 0L

    override fun onFrame(frame: TrackedFrame, width: Int, height: Int, rim: RimObs?): LiveStatus {
        buffer.addLast(frame)
        while (buffer.isNotEmpty() && frame.timeUs - buffer.first().timeUs > 6_000_000) buffer.removeFirst()
        if (frame.timeUs - lastRunUs > 500_000) {
            lastRunUs = frame.timeUs
            val frames = buffer.toList().mapIndexed { i, f -> TrackedFrame(i, f.timeUs, f.pose, f.ball) }
            ShootaroundAnalyzer.detectAttempts(frames, rim).forEach { a ->
                val key = frames[a.releaseFrame].timeUs / 200_000   // 0.2 s buckets dedupe re-detections
                // Only keep the outcome once the confirm window has passed.
                if (frame.timeUs - frames[a.endFrame].timeUs > 300_000) counted[key] = a.outcome
            }
        }
        val makes = counted.values.count { it == ShotOutcome.MAKE }
        val unsure = counted.values.count { it == ShotOutcome.UNCERTAIN }
        return LiveStatus(listOf(
            "Live preview: $makes / ${counted.size} made" + if (unsure > 0) " ($unsure unclear)" else "",
            if (rim == null) "Rim not found yet. Make sure it is in frame." else "Rim found"
        ))
    }
}
