// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.screen

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

sealed interface ScreenRemuxResult {
    data class Success(val sampleDurationMs: Long, val outputBytes: Long) : ScreenRemuxResult
    data class Failure(val reason: String, val cause: Throwable? = null) : ScreenRemuxResult
}

data class ScreenSample(val ptsUs: Long, val size: Int, val offset: Long, val isKeyFrame: Boolean)
data class AnnexBValidation(
    val validSamples: List<ScreenSample>,
    val sps: ByteArray?,
    val pps: ByteArray?,
    val hasIdr: Boolean,
    val truncatedFileSize: Long,
)

fun interface ScreenRemuxer {
    fun remux(attemptDir: File, h264File: File, indexFile: File, recordFile: File, mp4File: File): ScreenRemuxResult
}

object AnnexBParser {
    // Only read the committed prefix. Never truncate the independently held original.
    fun validateAndTruncate(h264File: File, indexFile: File): AnnexBValidation {
        var offset = 0L
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        val frames = mutableListOf<ScreenSample>()
        if (!h264File.isFile || !indexFile.isFile) return AnnexBValidation(frames, sps, pps, false, 0L)
        RandomAccessFile(h264File, "r").use { raw ->
            indexFile.inputStream().buffered().use { reader ->
                while (true) {
                    val line = readCommittedLine(reader) ?: break
                    val fields = line.split(',')
                    if (fields.size !in 2..3) break
                    val pts = fields[0].toLongOrNull() ?: break
                    val size = fields[1].toIntOrNull() ?: break
                    val flags = if (fields.size == 3) fields[2].toIntOrNull() ?: break else 0
                    if (size !in 1..AndroidScreenEncoder.MAX_SAMPLE_BYTES || size.toLong() > raw.length() - offset) break
                    val bytes = ByteArray(size)
                    raw.readFully(bytes)
                    var isFrame = false
                    var key = false
                    forEachNal(bytes) { start, end ->
                        when (bytes[start].toInt() and 31) {
                            7 -> sps = byteArrayOf(0, 0, 0, 1) + bytes.copyOfRange(start, end)
                            8 -> pps = byteArrayOf(0, 0, 0, 1) + bytes.copyOfRange(start, end)
                            5 -> { isFrame = true; key = true }
                            1 -> isFrame = true
                        }
                    }
                    if (isFrame && flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                        (frames.isEmpty() || pts > frames.last().ptsUs)) {
                        // Recovery starts at an IDR; earlier inter frames cannot decode independently.
                        if (key || frames.isNotEmpty()) frames += ScreenSample(pts, size, offset, key)
                    }
                    offset += size
                }
            }
        }
        return AnnexBValidation(frames, sps, pps, frames.any { it.isKeyFrame }, offset)
    }

    private fun readCommittedLine(input: java.io.InputStream): String? {
        val bytes = ByteArray(128)
        var count = 0
        while (count < bytes.size) {
            val value = input.read()
            if (value < 0) return null
            if (value == 10) return String(bytes, 0, count, Charsets.US_ASCII)
            bytes[count++] = value.toByte()
        }
        return null
    }

    private inline fun forEachNal(bytes: ByteArray, consume: (Int, Int) -> Unit) {
        var start = -1
        var i = 0
        while (i + 2 < bytes.size) {
            val four = i + 3 < bytes.size && bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() &&
                bytes[i + 2] == 0.toByte() && bytes[i + 3] == 1.toByte()
            val three = bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() && bytes[i + 2] == 1.toByte()
            if (four || three) {
                val prefix = if (four) 4 else 3
                if (start >= 0 && start < i) consume(start, i)
                start = i + prefix
                i += prefix
            } else i++
        }
        if (start >= 0 && start < bytes.size) consume(start, bytes.size)
    }
}

class AndroidScreenRemuxer(private val diag: (String) -> Unit = {}) : ScreenRemuxer {
    override fun remux(attemptDir: File, h264File: File, indexFile: File, recordFile: File, mp4File: File): ScreenRemuxResult {
        val temp = File(attemptDir, "remux.tmp.mp4")
        try {
            val parseStarted = System.nanoTime()
            val validation = AnnexBParser.validateAndTruncate(h264File, indexFile)
            val parseEnded = System.nanoTime()
            val validated = validation.validSamples
            if (validated.isEmpty() || validation.sps == null || validation.pps == null || !validation.hasIdr) {
                return ScreenRemuxResult.Failure("no_decodable_frames")
            }
            val info = ScreenRecordInfo.parse(recordFile.readText()) ?: return ScreenRemuxResult.Failure("invalid_record")
            val firstPts = validated.first().ptsUs
            val allowedUs = ((minOf(info.openEndEpochMs ?: info.windowEndEpochMs, info.windowEndEpochMs) -
                (info.firstFrameEpochMs ?: info.captureStartEpochMs)) * 1_000L).coerceAtLeast(1L)
            val frames = validated.takeWhile { it.ptsUs - firstPts < allowedUs }
            val lastPts = frames.last().ptsUs - firstPts
            val frameUs = if (frames.size > 1) (frames.last().ptsUs - frames[frames.lastIndex - 1].ptsUs)
                .coerceIn(1L, 1_000_000L) else 1_000_000L / info.frameRate
            val durationUs = (lastPts + frameUs).coerceAtMost(allowedUs).coerceAtLeast(lastPts + 1L)
            val muxer = MediaMuxer(temp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var writeEnded = 0L
            try {
                val format = MediaFormat.createVideoFormat(ScreenConstants.MIME_TYPE, info.width, info.height)
                format.setByteBuffer("csd-0", ByteBuffer.wrap(validation.sps))
                format.setByteBuffer("csd-1", ByteBuffer.wrap(validation.pps))
                val track = muxer.addTrack(format)
                muxer.start()
                val bufferInfo = MediaCodec.BufferInfo()
                // Reuse one bounded direct buffer. Per-frame heap buffers and
                // RandomAccessFile reads add avoidable work between encoders.
                val data = ByteBuffer.allocateDirect(frames.maxOf { it.size })
                FileInputStream(h264File).channel.use { raw ->
                    var cursor = 0L
                    for (sample in frames) {
                        if (cursor != sample.offset) raw.position(sample.offset)
                        data.clear()
                        data.limit(sample.size)
                        while (data.hasRemaining()) check(raw.read(data) > 0) { "screen sample truncated" }
                        cursor = sample.offset + sample.size
                        data.flip()
                        bufferInfo.set(0, sample.size, sample.ptsUs - firstPts,
                            if (sample.isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        muxer.writeSampleData(track, data, bufferInfo)
                    }
                }
                // MediaMuxer uses this explicit EOS timestamp for the final frame duration.
                bufferInfo.set(0, 0, durationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                muxer.writeSampleData(track, ByteBuffer.allocate(0), bufferInfo)
                writeEnded = System.nanoTime()
                muxer.stop()
            } finally {
                muxer.release()
            }
            val muxEnded = System.nanoTime()
            check(temp.length() <= ScreenConstants.MAX_MP4_BYTES) { "screen container exceeds portion budget" }
            FileOutputStream(temp, true).use { it.fd.sync() }
            Files.move(temp.toPath(), mp4File.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            FileChannel.open(attemptDir.toPath(), StandardOpenOption.READ).use { it.force(true) }
            val syncEnded = System.nanoTime()
            diag("screen event=remux-timing samples=${frames.size} parseMs=${(parseEnded - parseStarted) / 1_000_000L} writeMs=${(writeEnded - parseEnded) / 1_000_000L} closeMs=${(muxEnded - writeEnded) / 1_000_000L} syncMs=${(syncEnded - muxEnded) / 1_000_000L}")
            return ScreenRemuxResult.Success(maxOf(1L, durationUs / 1_000L), mp4File.length())
        } catch (t: Throwable) {
            temp.delete()
            return ScreenRemuxResult.Failure("remux_failed", t)
        }
    }
}
