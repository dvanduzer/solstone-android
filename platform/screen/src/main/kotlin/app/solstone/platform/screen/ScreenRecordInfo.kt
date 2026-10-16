// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.screen

data class ScreenRecordInfo(
    val id: String,
    val captureStartEpochMs: Long,
    val zoneId: String,
    val utcOffsetSeconds: Int,
    val windowStartEpochMs: Long,
    val windowEndEpochMs: Long,
    val width: Int,
    val height: Int,
    val bitRate: Int = ScreenConstants.BIT_RATE,
    val frameRate: Int = ScreenConstants.FRAME_RATE,
    val encoder: String = "h264",
    val container: String = "mp4",
    val openEndEpochMs: Long? = null,
    val sampleDurationMs: Long? = null,
    val firstFrameEpochMs: Long? = null,
) {
    fun captureBounds(durationMs: Long): Pair<Long, Long> {
        val first = firstFrameEpochMs ?: captureStartEpochMs
        return first to minOf(first + durationMs, openEndEpochMs ?: windowEndEpochMs, windowEndEpochMs)
    }

    fun serialize(): String = buildString {
        appendLine("id=$id")
        appendLine("captureStartEpochMs=$captureStartEpochMs")
        appendLine("zoneId=$zoneId")
        appendLine("utcOffsetSeconds=$utcOffsetSeconds")
        appendLine("windowStartEpochMs=$windowStartEpochMs")
        appendLine("windowEndEpochMs=$windowEndEpochMs")
        appendLine("width=$width")
        appendLine("height=$height")
        appendLine("bitRate=$bitRate")
        appendLine("frameRate=$frameRate")
        appendLine("encoder=$encoder")
        appendLine("container=$container")
        if (openEndEpochMs != null) {
            appendLine("openEndEpochMs=$openEndEpochMs")
        }
        if (sampleDurationMs != null && sampleDurationMs > 0L) {
            appendLine("sampleDurationMs=$sampleDurationMs")
        }
        if (firstFrameEpochMs != null) {
            appendLine("firstFrameEpochMs=$firstFrameEpochMs")
        }
    }

    companion object {
        fun parse(text: String): ScreenRecordInfo? {
            val map = text.lines()
                .filter { it.contains("=") }
                .associate { line ->
                    val idx = line.indexOf('=')
                    line.substring(0, idx).trim() to line.substring(idx + 1).trim()
                }
            val id = map["id"] ?: return null
            val captureStart = map["captureStartEpochMs"]?.toLongOrNull() ?: return null
            val zoneId = map["zoneId"] ?: return null
            val utcOffsetSeconds = map["utcOffsetSeconds"]?.toIntOrNull() ?: return null
            val windowStart = map["windowStartEpochMs"]?.toLongOrNull() ?: return null
            val windowEnd = map["windowEndEpochMs"]?.toLongOrNull() ?: return null
            val width = map["width"]?.toIntOrNull() ?: return null
            val height = map["height"]?.toIntOrNull() ?: return null
            val bitRate = map["bitRate"]?.toIntOrNull() ?: ScreenConstants.BIT_RATE
            val frameRate = map["frameRate"]?.toIntOrNull() ?: ScreenConstants.FRAME_RATE
            val encoder = map["encoder"] ?: "h264"
            val container = map["container"] ?: "mp4"
            val openEndEpochMs = map["openEndEpochMs"]?.toLongOrNull()
            val sampleDurationMs = map["sampleDurationMs"]?.toLongOrNull()
            val firstFrameEpochMs = map["firstFrameEpochMs"]?.toLongOrNull()
            return ScreenRecordInfo(
                id = id,
                captureStartEpochMs = captureStart,
                zoneId = zoneId,
                utcOffsetSeconds = utcOffsetSeconds,
                windowStartEpochMs = windowStart,
                windowEndEpochMs = windowEnd,
                width = width,
                height = height,
                bitRate = bitRate,
                frameRate = frameRate,
                encoder = encoder,
                container = container,
                openEndEpochMs = openEndEpochMs,
                sampleDurationMs = sampleDurationMs,
                firstFrameEpochMs = firstFrameEpochMs,
            )
        }
    }
}
