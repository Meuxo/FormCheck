package com.meuxo.formcheck.ui.components

import com.meuxo.formcheck.domain.model.Metric
import com.meuxo.formcheck.domain.model.MetricUnit
import com.meuxo.formcheck.domain.model.Units
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

object Format {
    /** Value with unit, converted to the user's units. "Not measured" when there is no value. */
    fun value(m: Metric, units: Units): String {
        val v = m.value ?: return "Not measured"
        return value(v, m.unit, units)
    }

    fun value(v: Double, unit: MetricUnit, units: Units): String = when (unit) {
        MetricUnit.DEGREES -> "${v.roundToInt()}°"
        MetricUnit.METERS -> if (units == Units.METRIC) "%.2f m".format(v) else feetInches(v)
        MetricUnit.CENTIMETERS -> if (units == Units.METRIC) "%.1f cm".format(v) else "%.1f in".format(v / 2.54)
        MetricUnit.MILLISECONDS -> "${v.roundToInt()} ms"
        MetricUnit.PERCENT -> "${v.roundToInt()}%"
        MetricUnit.PER_SECOND -> "%.1f /s".format(v)
        MetricUnit.METERS_PER_SECOND -> if (units == Units.METRIC) "%.2f m/s".format(v) else "%.1f ft/s".format(v / 0.3048)
        MetricUnit.COUNT -> "${v.roundToInt()}"
    }

    fun feetInches(m: Double): String {
        val totalIn = m / 0.0254
        val ft = (totalIn / 12).toInt()
        val inch = totalIn - ft * 12
        return "$ft ft %.1f in".format(inch)
    }

    fun bytes(b: Long): String = when {
        b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
        b >= 1L shl 10 -> "%.0f KB".format(b / (1L shl 10).toDouble())
        else -> "$b B"
    }

    fun date(ts: Long): String = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(Date(ts))
    fun shortDate(ts: Long): String = SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ts))
}
