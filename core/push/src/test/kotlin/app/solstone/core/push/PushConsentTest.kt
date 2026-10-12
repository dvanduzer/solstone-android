// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.push

import app.solstone.core.identity.ObtainResult
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.identity.PushKeyAccess
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PushConsentTest {
    private val key = ByteArray(32) { 7 }
    private val generation = PairingGeneration("fixture", "fixture")
    private val keys = object : PushKeyAccess {
        override fun readPushKey(generation: PairingGeneration): ByteArray = key
        override fun obtainPushKey(generation: PairingGeneration): ObtainResult = ObtainResult.Obtained(key)
    }

    private fun coordinator(directory: File, enabled: Boolean = true) = PushRegistrationCoordinator(
        directory, null, enabled, keys, { generation }, log = {}, enqueue = {},
    )

    private fun envelope(kind: String): ByteArray {
        val json = """{"v":1,"at":"2026-10-12T00:00:00Z","kind":"$kind","title":"$kind","body":"fixture","open":"/app/health/"}""".toByteArray()
        val padded = ByteArray(1024)
        json.copyInto(padded)
        padded[json.size] = 0x80.toByte()
        // Fixed key and nonce belong only to this deterministic test fixture.
        val nonce = ByteArray(12) { 3 }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(byteArrayOf(1))
        return byteArrayOf(1) + nonce + cipher.doFinal(padded)
    }

    @Test
    fun queuedMessagesAndFallbacksAreQuietWhileOffAndResumeWhenEnabled() {
        val directory = Files.createTempDirectory("push-consent").toFile()
        val coordinator = coordinator(directory)
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val posts = CopyOnWriteArrayList<String>()
        val handler = PushMessageHandler(
            keys, { generation }, coordinator.guardNotifier(PushNotifier { _, title, _, _ -> posts.add(title) }),
            log = {}, drawId = { 1000 },
        )
        val serial = PushEventSerial(coordinator, handler, executor)
        val test = envelope("test")
        val weekly = envelope("weekly_summary")
        val badAuth = test.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        try {
            val blocker = executor.submit { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            coordinator.setOwnerOn(true)
            assertTrue(coordinator.ownerOn)
            serial.onMessage(test, true)
            serial.onMessage(weekly, true)
            serial.onMessage(badAuth, true)
            serial.onMessage(test, false)
            coordinator.setOwnerOn(false)
            assertFalse(coordinator.ownerOn)
            release.countDown()
            blocker.get(5, TimeUnit.SECONDS)
            executor.submit {}.get(5, TimeUnit.SECONDS)
            assertTrue(posts.isEmpty())

            // Messages arriving later are fenced at the same final boundary.
            serial.onMessage(test, true)
            serial.onMessage(test, false)
            executor.submit {}.get(5, TimeUnit.SECONDS)
            assertTrue(posts.isEmpty())

            coordinator.setOwnerOn(true)
            serial.onMessage(test, true)
            serial.onMessage(weekly, true)
            serial.onMessage(badAuth, true)
            serial.onMessage(test, false)
            executor.submit {}.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("test", "weekly_summary", "solstone", "solstone"), posts.toList())
        } finally {
            release.countDown()
            serial.close()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            coordinator.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun anAlreadyStartedPostFinishesBeforeTurnOffAndLaterPostsAreQuiet() {
        val directory = Files.createTempDirectory("push-consent-order").toFile()
        val coordinator = coordinator(directory)
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val offStarted = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        val notifier = coordinator.guardNotifier(PushNotifier { _, _, _, _ ->
            events.add("post-start")
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            events.add("post-end")
        })
        val off = Thread {
            offStarted.countDown()
            coordinator.setOwnerOn(false)
            events.add("off")
        }
        try {
            coordinator.setOwnerOn(true)
            val post = executor.submit { notifier.post(1000, "fixture", "fixture", null) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            off.start()
            assertTrue(offStarted.await(5, TimeUnit.SECONDS))
            // Establish that turn-off actually reached the contested post boundary.
            // Merely timing a negative latch would allow an unscheduled thread to pass.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (off.state != Thread.State.BLOCKED && off.isAlive && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertEquals(Thread.State.BLOCKED, off.state)
            release.countDown()
            post.get(5, TimeUnit.SECONDS)
            off.join(5_000)
            assertFalse(off.isAlive)
            assertFalse(coordinator.ownerOn)
            notifier.post(1001, "later", "fixture", null)
            assertEquals(listOf("post-start", "post-end", "off"), events.toList())
        } finally {
            release.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            if (off.state != Thread.State.NEW) off.join(5_000)
            coordinator.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun defaultOffPersistedOffResetAndDisabledAllPreventPosts() {
        val directory = Files.createTempDirectory("push-consent-state").toFile()
        val posts = mutableListOf<Int>()
        val notifier = PushNotifier { id, _, _, _ -> posts.add(id) }
        try {
            coordinator(directory).use { first ->
                val guarded = first.guardNotifier(notifier)
                guarded.post(1000, "fixture", "fixture", null)
                assertTrue(posts.isEmpty())
                first.setOwnerOn(true)
                guarded.post(1001, "fixture", "fixture", null)
                first.setOwnerOn(false)
            }
            coordinator(directory).use { restarted ->
                val guarded = restarted.guardNotifier(notifier)
                assertFalse(restarted.ownerOn)
                guarded.post(1002, "fixture", "fixture", null)
                restarted.setOwnerOn(true)
                guarded.post(1003, "fixture", "fixture", null)
                restarted.reset()
                guarded.post(1004, "fixture", "fixture", null)
                restarted.setOwnerOn(true)
            }
            coordinator(directory, enabled = false).use { disabled ->
                assertFalse(disabled.ownerOn)
                disabled.guardNotifier(notifier).post(1005, "fixture", "fixture", null)
            }
            assertEquals(listOf(1001, 1003), posts)
        } finally {
            directory.deleteRecursively()
        }
    }
}
