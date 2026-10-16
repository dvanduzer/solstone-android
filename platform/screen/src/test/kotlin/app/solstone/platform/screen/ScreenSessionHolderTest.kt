// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.screen

import android.content.Intent
import android.util.DisplayMetrics
import app.solstone.platform.fgs.ScreenConsentToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScreenSessionHolderTest {

    private class FakeVirtualDisplay : ScreenVirtualDisplay {
        var currentSurface: Any? = null
        var lastResize: Triple<Int, Int, Int>? = null
        var released = false

        override fun setSurface(surface: Any?) {
            currentSurface = surface
        }

        override fun resize(width: Int, height: Int, dpi: Int) {
            lastResize = Triple(width, height, dpi)
        }

        override fun release() {
            released = true
        }
    }

    private class FakeProjection(private val onVirtualDisplayCreated: () -> Unit) : ScreenProjection {
        var callback: ScreenProjectionCallback? = null
        var stopped = false
        val displays = mutableListOf<FakeVirtualDisplay>()

        override fun registerCallback(callback: ScreenProjectionCallback) {
            this.callback = callback
        }

        override fun createVirtualDisplay(
            name: String,
            width: Int,
            height: Int,
            dpi: Int,
            surface: Any?,
            flags: Int,
        ): ScreenVirtualDisplay {
            onVirtualDisplayCreated()
            val display = FakeVirtualDisplay()
            displays += display
            return display
        }

        override fun stop() {
            stopped = true
        }
    }

    private class FakeProjectionManager : ScreenProjectionManager {
        private var getMediaProjectionCounter = 0
        private var createVirtualDisplayCounter = 0
        val projections = mutableListOf<FakeProjection>()

        override val getMediaProjectionCount: Int
            get() = getMediaProjectionCounter

        override val createVirtualDisplayCount: Int
            get() = createVirtualDisplayCounter

        override fun acquireProjection(token: ScreenConsentToken): ScreenProjection {
            getMediaProjectionCounter++
            val proj = FakeProjection { createVirtualDisplayCounter++ }
            projections += proj
            return proj
        }
    }

    private fun testMetrics(): DisplayMetrics = DisplayMetrics().apply {
        widthPixels = 1080
        heightPixels = 1920
        densityDpi = 320
    }

    @Test
    fun projectionCountsAndStaleOnStopBehavior() {
        val manager = FakeProjectionManager()
        var sessionEndedCalls = 0
        val holder = ScreenSessionHolder(
            projectionManager = manager,
            getDisplayMetrics = ::testMetrics,
            onSessionEnded = { sessionEndedCalls++ },
        )

        // Default off
        assertFalse(holder.isSessionLive())
        assertEquals(0, manager.getMediaProjectionCount)
        assertEquals(0, manager.createVirtualDisplayCount)

        // One token -> one getMediaProjection, one createVirtualDisplay
        val token1 = ScreenConsentToken(resultCode = -1, data = Intent(), generation = 1L)
        val started1 = holder.startSession(service = null, token = token1)
        assertTrue(started1)
        assertTrue(holder.isSessionLive())
        assertEquals(1, manager.getMediaProjectionCount)
        assertEquals(1, manager.createVirtualDisplayCount)

        val firstDisplay = holder.currentVirtualDisplay.get() as FakeVirtualDisplay
        assertNotNull(firstDisplay)
        assertFalse(firstDisplay.released)

        // A second start with the same generation does not increment either count
        val startedSameGen = holder.startSession(service = null, token = token1)
        assertTrue(startedSameGen)
        assertEquals(1, manager.getMediaProjectionCount)
        assertEquals(1, manager.createVirtualDisplayCount)

        val firstProjection = manager.projections[0]
        val firstCallback = requireNotNull(firstProjection.callback)

        // Start a second session with a new generation
        val token2 = ScreenConsentToken(resultCode = -1, data = Intent(), generation = 2L)
        val started2 = holder.startSession(service = null, token = token2)
        assertTrue(started2)
        assertEquals(2, manager.getMediaProjectionCount)
        assertEquals(2, manager.createVirtualDisplayCount)
        assertTrue(firstDisplay.released)
        assertTrue(firstProjection.stopped)

        val secondDisplay = holder.currentVirtualDisplay.get() as FakeVirtualDisplay
        assertNotNull(secondDisplay)
        assertFalse(secondDisplay.released)

        // Stale onStop from the first projection does not release the new display
        firstCallback.onStop()
        assertTrue(holder.isSessionLive())
        assertFalse(secondDisplay.released)
        assertEquals(0, sessionEndedCalls)

        // Rollover setSurface and resize do not increment createVirtualDisplay
        val currentDisplaysCount = manager.createVirtualDisplayCount
        secondDisplay.setSurface("dummy-surface-1")
        secondDisplay.setSurface(null)
        holder.onDisplayResized(720, 1280, 320)
        assertEquals(currentDisplaysCount, manager.createVirtualDisplayCount)
        assertEquals(Triple(720, 1280, 320), secondDisplay.lastResize)
    }
}
