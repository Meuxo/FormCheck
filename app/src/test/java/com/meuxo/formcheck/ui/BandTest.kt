package com.meuxo.formcheck.ui

import com.meuxo.formcheck.domain.model.Metric
import com.meuxo.formcheck.domain.model.MetricId
import com.meuxo.formcheck.ui.components.Band
import org.junit.Assert.assertEquals
import org.junit.Test

class BandTest {
    @Test fun `spec colour bands`() {
        assertEquals(Band.GOOD, Band.of(100)); assertEquals(Band.GOOD, Band.of(80))
        assertEquals(Band.OK, Band.of(79)); assertEquals(Band.OK, Band.of(60))
        assertEquals(Band.LOW, Band.of(59)); assertEquals(Band.LOW, Band.of(0))
    }

    @Test fun `not measured is gray, measured without target is info`() {
        assertEquals(Band.NONE, Band.of(Metric.notMeasured(MetricId.ENTRY_ANGLE, "Rim not located")))
        assertEquals(Band.INFO, Band.of(Metric.measured(MetricId.RELEASE_HEIGHT, 2.4, 0.8, null)))
    }

    @Test fun `every band has a distinct icon so colour is never the only signal`() {
        assertEquals(Band.entries.size, Band.entries.map { it.icon }.toSet().size)
    }
}
