// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.GraphMutationResult
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.platform.identity.file.FilePairingGraph
import app.solstone.platform.identity.file.SecretProtector
import app.solstone.platform.work.JournalRevokeOutcome
import app.solstone.platform.work.revokeThisDeviceOnJournal
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class PhoneMismatchOperationTest {
    @get:Rule val temp = TemporaryFolder()
    private val protector = object : SecretProtector {
        override fun protect(plaintext: ByteArray) = plaintext
        override fun unprotect(wrapped: ByteArray) = wrapped
    }

    private fun graph() = FilePairingGraph(
        identityFile = File(temp.root, "identity.tsv"),
        credentialFile = File(temp.root, "credential.pem"),
        endpointFile = File(temp.root, "endpoint.txt"),
        commitMarkerFile = File(temp.root, "pairing.commit"),
        protector = protector,
        pushKeyFile = File(temp.root, "push-key.bin"),
        pushKeyProtector = protector,
    )

    private fun install(graph: FilePairingGraph, id: String): PairingGraphSnapshot.Committed {
        val certificate = java.util.Base64.getEncoder().encodeToString(id.toByteArray())
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertIs<GraphMutationResult.Applied>(graph.installOrReplace(
            home = PairedHome(
                instanceId = id, homeLabel = id, relayOrigin = "https://link.solstone.app",
                caChainFingerprint = "sha256:ca", clientCertFingerprint = fingerprint,
                observerHandle = "phone", deviceToken = "test-$id", expiresAt = null,
                state = IdentityState.PAIRED,
            ),
            credential = ClientCredential(
                privateKeyPem = "-----BEGIN PRIVATE KEY-----\nkey\n-----END PRIVATE KEY-----\n",
                clientCertPem = "-----BEGIN CERTIFICATE-----\n$certificate\n-----END CERTIFICATE-----\n",
                caChainPem = listOf("-----BEGIN CERTIFICATE-----\nca\n-----END CERTIFICATE-----\n"),
            ),
            directEndpoint = null, isDirectAssociated = false,
        ))
        return assertIs<PairingGraphSnapshot.Committed>(graph.currentSnapshot())
    }

    @Test fun delayedCleanupCannotClearLaterPairingOrItsPushKey() {
        val graph = graph()
        val a = install(graph, "a")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        var pushReset = false
        try {
            val cleanup = executor.submit<GraphMutationResult> {
                runBlocking {
                    clearMismatchedPairing(graph, a.pairing,
                        revoke = {
                            entered.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                            JournalRevokeOutcome.REMOVED
                        },
                        afterCleared = { pushReset = true }, canClear = { true }, log = {},
                    )
                }
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val b = install(graph, "b")
            graph.obtainPushKey(b.pairing)
            val pushKey = graph.readPushKey(b.pairing)!!
            release.countDown()
            assertIs<GraphMutationResult.Conflict>(cleanup.get(10, TimeUnit.SECONDS))
            assertEquals(b.pairing, assertIs<PairingGraphSnapshot.Committed>(graph.currentSnapshot()).pairing)
            assertContentEquals(pushKey, graph.readPushKey(b.pairing))
            assertFalse(pushReset)
            assertEquals(b.pairing, assertIs<PairingGraphSnapshot.Committed>(graph().currentSnapshot()).pairing)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun staleRevocationIsRefusedBeforeOpeningLaterPairing() {
        val graph = graph()
        val a = install(graph, "a")
        val b = install(graph, "b")
        var opened = false
        assertEquals(JournalRevokeOutcome.UNREACHED, revokeThisDeviceOnJournal(
            graph,
            openDirect = { _, _ -> opened = true; error("must not open") },
            openRelay = { opened = true; error("must not open") },
            expectedPairing = a.pairing,
        ))
        assertFalse(opened)
        assertEquals(b.pairing, assertIs<PairingGraphSnapshot.Committed>(graph.currentSnapshot()).pairing)
    }

    @Test fun cleanupCannotErasePairingConfirmedWhileItWasPending() = runBlocking {
        val graph = graph()
        val a = install(graph, "a")
        val result = clearMismatchedPairing(graph, a.pairing,
            revoke = { JournalRevokeOutcome.UNREACHED },
            afterCleared = { error("must not reset") }, canClear = { false }, log = {},
        )
        assertIs<GraphMutationResult.Conflict>(result)
        assertEquals(a.pairing, assertIs<PairingGraphSnapshot.Committed>(graph.currentSnapshot()).pairing)
    }

    @Test fun currentUnconfirmedPairingCanBeCleared() = runBlocking {
        val graph = graph()
        val a = install(graph, "a")
        var pushReset = false
        val result = clearMismatchedPairing(graph, a.pairing,
            revoke = { JournalRevokeOutcome.UNREACHED },
            afterCleared = { pushReset = true }, canClear = { true }, log = {},
        )
        assertIs<GraphMutationResult.Cleared>(result)
        assertIs<PairingGraphSnapshot.Absent>(graph.currentSnapshot())
        assertTrue(pushReset)
    }
}
