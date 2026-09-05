// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlin.test.Test
import kotlin.test.assertEquals

/// Translated from Tests/TrainingLoadTests.swift.
class TrainingLoadTests {

    @Test
    fun cr10UsesTheTwoDocumentedFivePointMappings() {
        assertEquals(2.0, TrainingLoad.cr10(RPE.easy))
        assertEquals(4.0, TrainingLoad.cr10(RPE.comfortable))
        assertEquals(6.0, TrainingLoad.cr10(RPE.solid))
        assertEquals(8.0, TrainingLoad.cr10(RPE.hard))
        assertEquals(10.0, TrainingLoad.cr10(RPE.maximal))

        assertEquals(0.0, TrainingLoad.cr10(FingerStrain.nothing))
        assertEquals(2.5, TrainingLoad.cr10(FingerStrain.light))
        assertEquals(5.0, TrainingLoad.cr10(FingerStrain.worked))
        assertEquals(7.5, TrainingLoad.cr10(FingerStrain.taxed))
        assertEquals(10.0, TrainingLoad.cr10(FingerStrain.wrecked))
    }

    @Test
    fun sessionLoadIsIntensityTimesMinutes() {
        assertEquals(240.0, TrainingLoad.sessionLoad(cr10 = 8.0, minutes = 30))
    }

    @Test
    fun dailyFoldsEntriesAndKeepsGapDays() {
        val first = DayStamp(10)
        val entries = listOf(
            LoggedLoad(day = first, minutes = 60, rpe = RPE.easy, finger = FingerStrain.light),
            LoggedLoad(day = first, minutes = 30, rpe = RPE.hard, finger = null),
            LoggedLoad(day = first + 2, minutes = 20, rpe = null, finger = FingerStrain.wrecked),
        )

        val result = TrainingLoad.daily(entries, from = first, to = first + 3)

        assertEquals(listOf(first, first + 1, first + 2, first + 3), result.map { it.day })
        assertEquals(360.0, result[0].systemic)
        assertEquals(150.0, result[0].finger)
        assertEquals(TrainingLoad.DayLoad(day = first + 1, systemic = 0.0, finger = 0.0), result[1])
        assertEquals(0.0, result[2].systemic)
        assertEquals(200.0, result[2].finger)
        assertEquals(0.0, result[3].systemic)
        assertEquals(0.0, result[3].finger)
    }

    @Test
    fun rollingSumsUseInclusiveWindowEdges() {
        val first = DayStamp(20)
        val series = (0 until 5).map {
            TrainingLoad.DayLoad(
                day = first + it,
                systemic = (it + 1).toDouble(),
                finger = ((it + 1) * 10).toDouble(),
            )
        }

        assertEquals(6.0, TrainingLoad.rolling(series, days = 3, endingAt = first + 2).systemic)
        assertEquals(60.0, TrainingLoad.rolling(series, days = 3, endingAt = first + 2).finger)
        assertEquals(9.0, TrainingLoad.rolling(series, days = 2, endingAt = first + 4).systemic)
        assertEquals(90.0, TrainingLoad.rolling(series, days = 2, endingAt = first + 4).finger)
    }

    @Test
    fun nilRPEOrMinutesContributesZeroRatherThanAComputerGuess() {
        val day = DayStamp(40)
        val entries = listOf(
            LoggedLoad(day = day, minutes = null, rpe = RPE.maximal, finger = FingerStrain.wrecked),
            LoggedLoad(day = day, minutes = 30, rpe = null, finger = FingerStrain.taxed),
        )

        val result = TrainingLoad.daily(entries, from = day, to = day)[0]

        assertEquals(0.0, result.systemic)
        assertEquals(225.0, result.finger)
    }
}
