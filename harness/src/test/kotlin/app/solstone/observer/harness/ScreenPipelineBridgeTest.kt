// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.model.SilencedFact
import app.solstone.core.model.SourceKind
import app.solstone.core.observer.CapturePipeline
import app.solstone.core.segment.SealedSegment
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.segment.Segmenter
import app.solstone.core.segment.wireKeys
import app.solstone.core.sources.*
import app.solstone.core.spool.FileSpoolWriter
import app.solstone.core.spool.PayloadBytesProvider
import java.nio.file.Files
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScreenPipelineBridgeTest {
    @Test
    fun registryForwardsHoldAndLateFinalizationKeepsTwoPortionsAtTheirOwnWireTimes() {
        val root = Files.createTempDirectory("screen-pipeline")
        val grid = 1_800_000_000_000L
        val zone = ZoneId.of("UTC")
        val persisted = mutableListOf<SealedSegment>()
        var bound = false
        var released = 0
        val engine = object : ContinuousSourceEngine, SealWindowHoldClient {
            lateinit var hold: SealWindowHold
            override fun bindSealWindowHold(hold: SealWindowHold) { this.hold = hold; bound = true }
            override fun start(sink: EmissionSink) {
                hold.hold(MAIN_STREAM, grid)
                hold.hold(MAIN_STREAM, grid)
                fun emission(id: String, start: Long, end: Long, anchored: Boolean = false) = SourceEmission(
                    id, MAIN_STREAM, SourceKind.OBSERVER, start, end,
                    listOf(PayloadRef(if (anchored) "screen.mp4" else "audio.m4a", "video/mp4", 4, null)),
                    emptyMap(), emptyList(), independentTimeline = anchored)
                sink.emit(emission("audio", grid, grid + 300_000L))
                // Another source crosses the boundary before video finishes. The held window
                // must remain open, and neither screen portion may inherit the grid's start.
                sink.emit(emission("audio", grid + 306_000L, grid + 307_000L))
                hold.releaseAfterFeed(emission("screen", grid + 90_000L, grid + 100_000L, true))
                hold.releaseAfterFeed(emission("screen", grid + 110_000L, grid + 120_000L, true))
            }
            override fun stop() {}
            override fun condition() = SourceCondition(true, true, true, false, false, SilencedFact.UNKNOWN)
        }
        val registry = sourceRegistry(registrations = listOf(SourceRegistration("screen", engine)))
        registry.setWish("screen", SourceWish.On)
        val pipeline = CapturePipeline(Segmenter(zone), FileSpoolWriter(root),
            { segment, result, _ ->
                assertEquals(segment.key, result.manifest.key)
                persisted += segment
            }, object : PayloadBytesProvider {
                override fun open(payload: SegmentPayload) = byteArrayOf(1, 2, 3, 4).inputStream()
                override fun release(payload: SegmentPayload) { released++ }
            }, registry.engines, { grid + 307_000L }, 60_000L)
        try {
            pipeline.start()
            pipeline.stop()
            assertTrue(bound)
            assertEquals(4, persisted.size)
            assertEquals(4, released)
            val portions = persisted.filter { it.payloads.single().sourceId == "screen" }
            assertEquals(listOf(90_000L, 110_000L), portions.map { it.wireKeys.startEpochMs - grid })
            assertEquals(listOf(10_000L, 10_000L), portions.map { it.wireKeys.endEpochMs - it.wireKeys.startEpochMs })
            assertEquals(portions.map { wireKeys(it.payloads.single().captureStartEpochMs,
                it.payloads.single().captureEndEpochMs, zone).segment }, portions.map { it.key.segment })
        } finally { root.toFile().deleteRecursively() }
    }
}
