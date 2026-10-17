// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import app.solstone.core.model.SourceState
import app.solstone.observer.scaffold.ObserverAppContainer
import app.solstone.observer.scaffold.ObserverApplication
import app.solstone.observer.harness.SourceWish
import app.solstone.core.model.ReasonCode
import app.solstone.platform.fgs.ObserverForegroundService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ObserverForegroundRehydrateRuntimeTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        resetObserverRuntime()
        ObserverForegroundService.stop(context)
        waitUntil("prior service destruction") { ObserverForegroundService.heldCaptureForegroundTypes == null }
        resetPersistence(context)
    }

    @After
    fun tearDown() {
        resetObserverRuntime()
        ObserverForegroundService.stop(context)
        waitUntil("service destruction") { ObserverForegroundService.heldCaptureForegroundTypes == null }
    }

    /**
     * AC8 red proof: without Application rehydrator registration, sticky foreground restarts could not dispatch rehydrate.
     */
    @Test
    fun applicationRegistersForegroundServiceRehydratorAndDispatchIsHonestWhenBlocked() {
        val application = context.applicationContext as ObserverApplication
        val hook = ObserverForegroundService.rehydrator
        assertNotNull(hook)
        assertNull(application.runtime.containerIfInitialized)
        ObserverForegroundService.dispatchRehydrate(hook)
        waitUntil("registered rehydrator creates runtime") {
            application.runtime.containerIfInitialized is ObserverAppContainer
        }
        val container = application.runtime.containerIfInitialized as ObserverAppContainer
        assertTrue(waitForRecovery(container))
        assertTrue(container.awaitBackgroundIdle())
        assertFalse(container.captureAuthority.isVisibleOwnerPresent())
        assertNull(ObserverForegroundService.heldCaptureForegroundTypes)
        container.sources.setWish("audio", SourceWish.On)
        container.controller.ensureObserving()
        assertTrue(container.controller.lastStartRefused)
        ObserverForegroundService.dispatchRehydrate(hook)
        assertTrue(container.awaitBackgroundIdle())
        val audio = container.sources.snapshot().sources.single { it.sourceId == "audio" }
        assertEquals(SourceWish.On, audio.wish)
        assertEquals(SourceState.NEEDS_ATTENTION, audio.state)
        assertEquals(ReasonCode.FOREGROUND_START_NOT_ALLOWED, audio.reason)
        assertTrue(container.controller.lastStartRefused)
        assertFalse(container.captureAuthority.isVisibleOwnerPresent())
        assertNull(ObserverForegroundService.heldCaptureForegroundTypes)
    }
}
