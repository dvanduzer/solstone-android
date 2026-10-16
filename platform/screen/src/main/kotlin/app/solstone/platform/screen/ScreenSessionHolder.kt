// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.screen

import android.content.Context
import android.util.DisplayMetrics
import app.solstone.platform.fgs.ScreenConsentToken
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class ScreenGeometry(val width: Int, val height: Int, val dpi: Int)

class ScreenSessionHolder(
    private val projectionManager: ScreenProjectionManager,
    private val getDisplayMetrics: () -> DisplayMetrics,
    private val onSessionEnded: () -> Unit,
) {
    private val lock = Any()
    private var activeProjection: ScreenProjection? = null
    private var activeVirtualDisplay: ScreenVirtualDisplay? = null
    private var lastConsumedGeneration = Long.MIN_VALUE
    private var activeToken: ScreenConsentToken? = null
    private val isLive = AtomicBoolean(false)
    private val sessionGeneration = AtomicLong(0L)
    val currentGeometry = AtomicReference<ScreenGeometry?>(null)
    val currentVirtualDisplay = AtomicReference<ScreenVirtualDisplay?>(null)

    fun isSessionLive(): Boolean = isLive.get()
    fun generation(): Long = sessionGeneration.get()

    fun startSession(service: Context? = null, token: ScreenConsentToken): Boolean {
        synchronized(lock) {
            if (isLive.get() && activeToken?.generation == token.generation) {
                return true
            }
            if (token.generation <= lastConsumedGeneration) return false
            lastConsumedGeneration = token.generation
            stopSession()
            val generation = sessionGeneration.incrementAndGet()
            return try {
                val projection = projectionManager.acquireProjection(token)
                activeProjection = projection
                projection.registerCallback(object : ScreenProjectionCallback {
                    override fun onStop() {
                        synchronized(lock) {
                            if (sessionGeneration.get() != generation) return
                            stopSession()
                        }
                        onSessionEnded()
                    }

                    override fun onCapturedContentResize(width: Int, height: Int) {
                        synchronized(lock) {
                            if (sessionGeneration.get() != generation) return
                            val metrics = getDisplayMetrics()
                            onDisplayResized(width, height, metrics.densityDpi)
                        }
                    }
                })

                val metrics = getDisplayMetrics()
                val res = ScreenConstants.calculateResolution(metrics.widthPixels, metrics.heightPixels)
                    ?: (1280 to 720)
                val width = res.first
                val height = res.second
                val dpi = if (metrics.densityDpi > 0) metrics.densityDpi else DisplayMetrics.DENSITY_DEFAULT

                val virtualDisplay = projection.createVirtualDisplay(
                    name = "solstone-screen",
                    width = width,
                    height = height,
                    dpi = dpi,
                    surface = null,
                )

                activeToken = token
                activeProjection = projection
                activeVirtualDisplay = virtualDisplay
                currentGeometry.set(ScreenGeometry(width, height, dpi))
                currentVirtualDisplay.set(virtualDisplay)
                isLive.set(true)
                true
            } catch (error: Throwable) {
                android.util.Log.w("ScreenSession", "projection start failed", error)
                stopSession()
                false
            }
        }
    }

    fun onDisplayResized(newWidth: Int, newHeight: Int, newDpi: Int) {
        synchronized(lock) {
            val display = activeVirtualDisplay ?: return
            val res = ScreenConstants.calculateResolution(newWidth, newHeight) ?: return
            val dpi = if (newDpi > 0) newDpi else DisplayMetrics.DENSITY_DEFAULT
            display.resize(res.first, res.second, dpi)
            currentGeometry.set(ScreenGeometry(res.first, res.second, dpi))
        }
    }

    fun stopIfGeneration(expected: Long): Boolean = synchronized(lock) {
        if (sessionGeneration.get() != expected) false else { stopSession(); true }
    }

    fun stopSession() {
        synchronized(lock) {
            sessionGeneration.incrementAndGet()
            isLive.set(false)
            activeToken = null
            currentVirtualDisplay.set(null)
            currentGeometry.set(null)
            runCatching { activeVirtualDisplay?.release() }
            activeVirtualDisplay = null
            runCatching { activeProjection?.stop() }
            activeProjection = null
        }
    }
}
