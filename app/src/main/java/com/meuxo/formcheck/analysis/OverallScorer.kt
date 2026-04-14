package com.meuxo.formcheck.analysis

import javax.inject.Inject
import javax.inject.Singleton

/** Weighted-average overall score (0..100). */
@Singleton
class OverallScorer @Inject constructor() {

    fun score(
        elbowFlare: Int,
        elbowAngle: Int,
        kneeBend: Int,
        setPoint: Int,
        balance: Int,
        guideHand: Int
    ): Int {
        val weighted =
            elbowFlare * 0.20 +
            elbowAngle * 0.20 +
            kneeBend  * 0.15 +
            setPoint  * 0.15 +
            balance   * 0.15 +
            guideHand * 0.15
        return weighted.toInt().coerceIn(0, 100)
    }
}
