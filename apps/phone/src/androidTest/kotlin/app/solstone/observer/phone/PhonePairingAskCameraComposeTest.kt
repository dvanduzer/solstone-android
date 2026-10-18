// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.ClientCredentialStore
import app.solstone.core.identity.IdentityStore
import app.solstone.core.model.PairedHome
import app.solstone.core.model.SilencedFact
import app.solstone.core.pl.DirectEndpoint
import app.solstone.core.pl.EndpointStore
import app.solstone.observer.formfactor.phone.PhonePairingEvent
import app.solstone.observer.harness.BundleExport
import app.solstone.observer.harness.EvidenceReader
import app.solstone.observer.harness.HarnessController
import app.solstone.observer.harness.HarnessEvidenceSegment
import app.solstone.observer.harness.HarnessExportResult
import app.solstone.observer.harness.HarnessPlStatus
import app.solstone.observer.harness.HarnessSyncState
import app.solstone.observer.harness.HeartbeatFreshness
import app.solstone.observer.harness.InMemoryDesiredObservingStore
import app.solstone.observer.harness.ObserverLifecycle
import app.solstone.observer.harness.PairProbe
import app.solstone.observer.harness.PlStatusProbe
import app.solstone.observer.harness.RelayPairProbe
import app.solstone.observer.harness.SourceRuntimeSnapshot
import app.solstone.observer.harness.SyncEnqueue
import app.solstone.observer.harness.VisibleCaptureOwnerRegistry
import app.solstone.observer.scaffold.CameraAsk
import app.solstone.platform.camera.still.SingleHolderCameraLock
import app.solstone.platform.fgs.PermissionStatus
import app.solstone.platform.fgs.PermissionStatusReader
import java.net.ConnectException
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhonePairingAskCameraComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun askForCameraDoesNotAskOnEnterAndDispatchesExpectedEvents() {
        var askCount = 0
        val dispatchedEvents = mutableListOf<PhonePairingEvent>()

        composeRule.setContent {
            MaterialTheme {
                PhonePairingScanning(
                    cameraAsk = CameraAsk.NotAsked,
                    hasCamera = false,
                    onAskForCamera = { askCount++ },
                    onScannerNeedsCamera = {},
                    dispatch = { dispatchedEvents.add(it) },
                    controller = failingController(),
                )
            }
        }

        composeRule.waitForIdle()
        assertEquals(0, askCount)

        composeRule.onNodeWithTag("pairingScanCode").performClick()
        composeRule.waitForIdle()
        assertEquals(1, askCount)

        composeRule.onNodeWithTag("pairingPaste").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(PhonePairingEvent.CloseScan), dispatchedEvents)
        assertEquals(1, askCount)
    }

    private fun failingController(): HarnessController =
        HarnessController(
            permissionStatusReader = PermissionStatusReader {
                PermissionStatus(
                    microphoneGranted = true,
                    cameraGranted = true,
                    locationGranted = true,
                    notificationsGranted = true,
                )
            },
            desiredObservingStore = InMemoryDesiredObservingStore(),
            cameraLock = SingleHolderCameraLock(),
            observerLifecycle = object : ObserverLifecycle {
                override fun start() = Unit
                override fun stop() = Unit
            },
            heartbeatFreshness = HeartbeatFreshness { true },
            pairProbe = PairProbe { _, _ -> throw ConnectException("refused") },
            relayPairProbe = RelayPairProbe { _, _ -> error("unexpected relay probe") },
            plStatusProbe = PlStatusProbe { HarnessPlStatus.NotPaired },
            syncEnqueue = object : SyncEnqueue {
                override fun enqueuePeriodic() = Unit
                override fun enqueueNow() = Unit
            },
            evidenceReader = object : EvidenceReader {
                override fun listEvidence(): List<HarnessEvidenceSegment> = emptyList()
                override fun pendingCount(): Int = 0
                override fun syncState(): HarnessSyncState = HarnessSyncState(0, null, null)
            },
            bundleExport = BundleExport { HarnessExportResult("", "", 0) },
            endpointStore = MemoryEndpointStore(),
            credentialStore = MemoryCredentialStore(),
            identityStore = MemoryIdentityStore(),
            sourceSnapshot = { SourceRuntimeSnapshot(false, false, true, SilencedFact.UNKNOWN) },
            deviceLabel = "phone-test",
            visibleCaptureAuthority = VisibleCaptureOwnerRegistry(),
            isUsableNetworkPresent = { true },
        )

    private class MemoryEndpointStore : EndpointStore {
        override fun save(endpoint: DirectEndpoint) = Unit
        override fun load(): DirectEndpoint? = null
        override fun clear() = Unit
    }

    private class MemoryCredentialStore : ClientCredentialStore {
        override fun save(credential: ClientCredential) = Unit
        override fun load(): ClientCredential? = null
        override fun clear() = Unit
    }

    private class MemoryIdentityStore : IdentityStore {
        override fun save(home: PairedHome) = Unit
        override fun load(): PairedHome? = null
        override fun clear() = Unit
    }
}
