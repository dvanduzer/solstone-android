// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.screen

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnnexBParserTest {
    @Test
    fun mixedNalPrefixesRetainConfigurationAndExactAcceptedSampleOffsets() {
        val root = Files.createTempDirectory("screen-annex-b-mixed").toFile()
        try {
            val config = byteArrayOf(0, 0, 1, 0x67, 42, 0, 0, 0, 1, 0x68, 43)
            val predicted = byteArrayOf(0, 0, 1, 0x41, 45)
            val mixed = byteArrayOf(0, 0, 0, 1, 0x09, 7, 0, 0, 1, 0x65, 44, 0, 0, 0, 1, 0x41, 46)
            val rawBytes = config + predicted + mixed + predicted + predicted + predicted + predicted
            val raw = root.resolve("capture.h264").apply { writeBytes(rawBytes) }
            val index = root.resolve("samples.idx").apply {
                writeText("0,${config.size},2\n100,${predicted.size},0\n200,${mixed.size},1\n" +
                    "200,${predicted.size},0\n199,${predicted.size},0\n300,${predicted.size},2\n400,${predicted.size},0\n")
            }
            val parsed = AnnexBParser.validateAndTruncate(raw, index)
            assertTrue(parsed.hasIdr)
            assertEquals(listOf(
                ScreenSample(200L, mixed.size, (config.size + predicted.size).toLong(), true),
                ScreenSample(400L, predicted.size, (rawBytes.size - predicted.size).toLong(), false),
            ), parsed.validSamples)
            assertContentEquals(byteArrayOf(0, 0, 0, 1, 0x67, 42), parsed.sps)
            assertContentEquals(byteArrayOf(0, 0, 0, 1, 0x68, 43), parsed.pps)
            assertEquals(rawBytes.size.toLong(), parsed.truncatedFileSize)
            assertContentEquals(rawBytes, raw.readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun configurationIsExcludedFromTimedFramesAndTornTailDoesNotModifyOriginal() {
        val root = Files.createTempDirectory("screen-annex-b").toFile()
        try {
            val config = byteArrayOf(0, 0, 0, 1, 0x67, 42, 0, 0, 0, 1, 0x68, 43)
            val idr = byteArrayOf(0, 0, 0, 1, 0x65, 44)
            val predicted = byteArrayOf(0, 0, 1, 0x41, 45)
            val original = config + idr + predicted + byteArrayOf(0, 0, 1)
            val raw = root.resolve("capture.h264").apply { writeBytes(original) }
            val index = root.resolve("samples.idx").apply {
                writeText("0,${config.size},2\n8000000,${idr.size},1\n8066666,${predicted.size},0\n8133333,90,0\n")
            }
            val parsed = AnnexBParser.validateAndTruncate(raw, index)
            assertTrue(parsed.hasIdr)
            assertEquals(listOf(8_000_000L, 8_066_666L), parsed.validSamples.map { it.ptsUs })
            assertContentEquals(byteArrayOf(0, 0, 0, 1, 0x67, 42), parsed.sps)
            assertContentEquals(byteArrayOf(0, 0, 0, 1, 0x68, 43), parsed.pps)
            assertContentEquals(original, raw.readBytes())
            assertEquals((config.size + idr.size + predicted.size).toLong(), parsed.truncatedFileSize)
            // Even a plausible legacy two-column row is uncommitted without its newline.
            raw.writeBytes(config + idr + predicted + idr)
            index.writeText("0,${config.size},2\n8000000,${idr.size},1\n8066666,${predicted.size},0\n8133333,${idr.size}")
            assertEquals(2, AnnexBParser.validateAndTruncate(raw, index).validSamples.size)
            assertContentEquals(config + idr + predicted + idr, raw.readBytes())
        } finally { root.deleteRecursively() }
    }
}
