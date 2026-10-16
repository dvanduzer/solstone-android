// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.screen

import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenPresentationTimeTest {
    @Test
    fun resolutionCalculationClampsAndRoundsToEven() {
        val res1 = ScreenConstants.calculateResolution(1080, 1920)
        assertEquals(720 to 1280, res1)

        val res2 = ScreenConstants.calculateResolution(1920, 1080)
        assertEquals(1280 to 720, res2)

        val res3 = ScreenConstants.calculateResolution(721, 1281)
        assertEquals(720 to 1280, res3)

        val resZero = ScreenConstants.calculateResolution(0, 100)
        assertEquals(null, resZero)
    }
}
