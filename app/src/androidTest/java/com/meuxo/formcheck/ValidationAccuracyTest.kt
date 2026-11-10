package com.meuxo.formcheck

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meuxo.formcheck.analysis.SessionAnalyzer
import com.meuxo.formcheck.domain.model.MetricId
import com.meuxo.formcheck.domain.model.SessionParams
import com.meuxo.formcheck.domain.model.SessionType
import com.meuxo.formcheck.domain.model.UserSettings
import com.meuxo.formcheck.tracking.Side
import com.meuxo.formcheck.tracking.pipeline.ClipTracker
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Runs the real pipeline (MediaCodec decode, MediaPipe pose, ball tracking, analysis) on the
 * clips in /validation and reports mean absolute error per metric against hand-measured values.
 * Skips when no ground truth exists, so an empty set never reads as "accurate".
 */
@RunWith(AndroidJUnit4::class)
class ValidationAccuracyTest {

    private data class Truth(val clip: String, val metric: MetricId, val value: Double)

    @Test
    fun meanAbsoluteErrorPerMetric() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().context          // test APK assets
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val rows = ctx.assets.open("ground_truth.csv").bufferedReader().readLines().drop(1)
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val c = line.split(",")
                val id = runCatching { MetricId.valueOf(c[1].trim()) }.getOrNull() ?: return@mapNotNull null
                c[2].trim().toDoubleOrNull()?.let { Truth(c[0].trim(), id, it) }
            }
        assumeTrue("No ground truth in validation/ground_truth.csv: accuracy has not been measured.", rows.isNotEmpty())

        val errors = HashMap<MetricId, MutableList<Double>>()
        val notMeasured = HashMap<MetricId, Int>()
        val tracker = ClipTracker(app)
        val analyzer = SessionAnalyzer()
        for ((clip, truths) in rows.groupBy { it.clip }) {
            val f = File(app.cacheDir, clip)
            ctx.assets.open("clips/$clip").use { input -> f.outputStream().use { input.copyTo(it) } }
            val type = if (truths.any { it.metric == MetricId.FLIGHT_TIME }) SessionType.JUMP else SessionType.FORM
            val clipData = tracker.track(f, ClipTracker.Options(trackBall = type != SessionType.JUMP))
            val result = analyzer.analyze(clipData, SessionParams(type), UserSettings(shootingHand = Side.RIGHT, heightCm = null))
            val metrics = (result.shots.firstOrNull()?.metrics.orEmpty() + result.metrics)
            for (t in truths) {
                val m = metrics.firstOrNull { it.id == t.metric }
                val v = m?.value
                if (v == null) notMeasured[t.metric] = (notMeasured[t.metric] ?: 0) + 1
                else errors.getOrPut(t.metric) { ArrayList() } += abs(v - t.value)
            }
        }
        val table = buildString {
            appendLine("metric,unit,n,mae,not_measured")
            (errors.keys + notMeasured.keys).toSet().sortedBy { it.ordinal }.forEach { id ->
                val e = errors[id].orEmpty()
                appendLine("${id.name},${id.unit.symbol},${e.size},${if (e.isEmpty()) "" else "%.2f".format(e.average())},${notMeasured[id] ?: 0}")
            }
        }
        Log.i("FormCheckValidation", "\n$table")
        File(app.getExternalFilesDir(null), "validation_results.csv").writeText(table)
    }
}
