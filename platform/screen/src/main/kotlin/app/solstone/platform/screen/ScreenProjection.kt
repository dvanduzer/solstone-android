// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.screen

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import app.solstone.platform.fgs.ScreenConsentToken
import java.util.concurrent.atomic.AtomicInteger

interface ScreenVirtualDisplay {
    fun setSurface(surface: Any?)
    fun resize(width: Int, height: Int, dpi: Int)
    fun release()
}

interface ScreenProjectionCallback {
    fun onStop()
    fun onCapturedContentResize(width: Int, height: Int)
}

interface ScreenProjection {
    fun registerCallback(callback: ScreenProjectionCallback)
    fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Any?,
        flags: Int = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
    ): ScreenVirtualDisplay
    fun stop()
}

interface ScreenProjectionManager {
    fun acquireProjection(token: ScreenConsentToken): ScreenProjection
    val getMediaProjectionCount: Int
    val createVirtualDisplayCount: Int
}

class AndroidScreenVirtualDisplay(private val virtualDisplay: VirtualDisplay) : ScreenVirtualDisplay {
    override fun setSurface(surface: Any?) {
        if (surface is Surface?) {
            virtualDisplay.surface = surface
        }
    }

    override fun resize(width: Int, height: Int, dpi: Int) {
        virtualDisplay.resize(width, height, dpi)
    }

    override fun release() {
        virtualDisplay.release()
    }
}

class AndroidScreenProjection(
    private val mediaProjection: MediaProjection,
    private val onVirtualDisplayCreated: () -> Unit,
) : ScreenProjection {
    override fun registerCallback(callback: ScreenProjectionCallback) {
        val cb = if (Build.VERSION.SDK_INT >= 34) {
            object : MediaProjection.Callback() {
                override fun onStop() {
                    callback.onStop()
                }

                override fun onCapturedContentResize(width: Int, height: Int) {
                    callback.onCapturedContentResize(width, height)
                }
            }
        } else {
            object : MediaProjection.Callback() {
                override fun onStop() {
                    callback.onStop()
                }
            }
        }
        mediaProjection.registerCallback(cb, Handler(Looper.getMainLooper()))
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
        val realSurface = surface as? Surface
        val display = mediaProjection.createVirtualDisplay(
            name,
            width,
            height,
            dpi,
            flags,
            realSurface,
            null,
            Handler(Looper.getMainLooper()),
        )
        return AndroidScreenVirtualDisplay(display)
    }

    override fun stop() {
        runCatching { mediaProjection.stop() }
    }
}

class AndroidScreenProjectionManager(private val context: Context) : ScreenProjectionManager {
    private val getMediaProjectionCounter = AtomicInteger(0)
    private val createVirtualDisplayCounter = AtomicInteger(0)

    override val getMediaProjectionCount: Int
        get() = getMediaProjectionCounter.get()

    override val createVirtualDisplayCount: Int
        get() = createVirtualDisplayCounter.get()

    override fun acquireProjection(token: ScreenConsentToken): ScreenProjection {
        getMediaProjectionCounter.incrementAndGet()
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        val projection = mpm.getMediaProjection(token.resultCode, token.data)
        requireNotNull(projection) { "MediaProjection returned null" }
        return AndroidScreenProjection(projection) {
            createVirtualDisplayCounter.incrementAndGet()
        }
    }
}
