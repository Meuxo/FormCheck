package com.meuxo.formcheck.live

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.meuxo.formcheck.tracking.Lm
import com.meuxo.formcheck.tracking.pipeline.PoseEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/** Result of the pre-recording checks (spec 8). Null = not checked yet / not available. */
data class SetupState(
    val fullBody: Boolean? = null,
    val fullBodyHint: String? = null,
    val lighting: Boolean? = null,
    val steady: Boolean? = null
)

/**
 * Pre-recording camera setup guide: full body in frame (pose, every 10th frame), adequate
 * lighting (mean luma of the Y plane), and phone steadiness (gyroscope). Thresholds are HEURISTIC.
 */
class SetupChecker(private val context: Context) : ImageAnalysis.Analyzer, SensorEventListener {
    private val _state = MutableStateFlow(SetupState())
    val state: StateFlow<SetupState> = _state.asStateFlow()
    private var pose: PoseEngine? = null
    private var n = 0
    private val gyro = ArrayDeque<Pair<Long, Double>>()
    private val sensors = context.getSystemService(SensorManager::class.java)

    fun start() {
        sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        sensors?.unregisterListener(this)
        runCatching { pose?.close() }; pose = null
    }

    override fun onSensorChanged(e: SensorEvent) {
        val mag = sqrt((e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble())
        gyro.addLast(e.timestamp to mag)
        while (gyro.isNotEmpty() && e.timestamp - gyro.first().first > 1_000_000_000L) gyro.removeFirst()
        // Below ~0.05 rad/s for a full second: on a tripod or propped up.
        _state.value = _state.value.copy(steady = gyro.maxOf { it.second } < 0.05)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun analyze(image: ImageProxy) {
        try {
            n++
            // Lighting from the luma plane (YUV_420_888 default format).
            val y = image.planes[0]
            val buf = y.buffer
            var sum = 0L; var count = 0
            val step = 16
            var i = 0
            while (i < buf.limit()) { sum += buf.get(i).toInt() and 0xFF; count++; i += step * y.pixelStride }
            val luma = if (count == 0) 0.0 else sum.toDouble() / count
            var s = _state.value.copy(lighting = luma in 55.0..235.0)
            if (n % 10 == 0) {
                if (pose == null) pose = PoseEngine(context, runningMode = com.google.mediapipe.tasks.vision.core.RunningMode.IMAGE)
                val bmp = upright(image)
                val p = pose!!.detect(bmp, 0).firstOrNull()
                val need = listOf(Lm.NOSE, Lm.LEFT_SHOULDER, Lm.RIGHT_SHOULDER, Lm.LEFT_HIP, Lm.RIGHT_HIP, Lm.LEFT_ANKLE, Lm.RIGHT_ANKLE, Lm.LEFT_FOOT_INDEX, Lm.RIGHT_FOOT_INDEX)
                s = if (p == null) s.copy(fullBody = false, fullBodyHint = "No one in frame") else {
                    val margin = 0.02
                    val inside = need.all { k -> p.visible(k, 0.5f) && p.px(k).x in bmp.width * margin..bmp.width * (1 - margin) && p.px(k).y in bmp.height * margin..bmp.height * (1 - margin) }
                    val feet = listOf(Lm.LEFT_FOOT_INDEX, Lm.RIGHT_FOOT_INDEX).all { p.visible(it, 0.5f) }
                    s.copy(fullBody = inside, fullBodyHint = when { inside -> null; !feet -> "Feet are cut off: step back or tilt down"; else -> "Move so your whole body fits in frame" })
                }
                bmp.recycle()
            }
            _state.value = s
        } catch (_: Throwable) {
        } finally { image.close() }
    }

    private fun upright(image: ImageProxy): Bitmap {
        val raw = image.toBitmap()
        val rot = image.imageInfo.rotationDegrees
        if (rot == 0) return raw
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rot.toFloat()) }, true).also { raw.recycle() }
    }
}
