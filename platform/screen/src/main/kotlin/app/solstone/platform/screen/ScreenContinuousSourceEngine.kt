// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.screen

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SilencedFact
import app.solstone.core.model.SourceKind
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.sources.ContinuousSourceEngine
import app.solstone.core.sources.EmissionSink
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.core.sources.PayloadRef
import app.solstone.core.sources.SealWindowHold
import app.solstone.core.sources.SealWindowHoldClient
import app.solstone.core.sources.SourceCondition
import app.solstone.core.sources.SourceEmission
import app.solstone.core.spool.PayloadBytesProvider
import app.solstone.core.spool.UnresolvedInterruption
import app.solstone.platform.power.StorageStatus
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class ScreenContinuousSourceEngine(
    private val outputDirectory: File,
    private val storageStatus: StorageStatus,
    private val remuxer: ScreenRemuxer,
    private val interruption: UnresolvedInterruption,
    private val sessionHolder: ScreenSessionHolder,
    private val encoderFactory: ScreenEncoderFactory = ScreenEncoderFactory { w, h, b, f -> AndroidScreenEncoder(w, h, b, f) },
    private val onFloorTripped: (() -> Unit)? = null,
    private val onFailed: (() -> Unit)? = null,
    private val nowProvider: () -> Long = System::currentTimeMillis,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val zoneProvider: () -> ZoneId = { ZoneId.systemDefault() },
    private val diag: (String) -> Unit = {},
    private val stopJoinTimeoutMs: Long = 10_000L,
) : ContinuousSourceEngine, PayloadBytesProvider, SealWindowHoldClient {
    private val running = AtomicBoolean(false)
    private val lifecycleLock = Any()
    @Volatile private var worker: Thread? = null
    private var pendingStart: EmissionSink? = null
    @Volatile private var hold: SealWindowHold? = null
    @Volatile private var failedReason: ReasonCode? = null
    @Volatile private var activeId: String? = null
    private data class Key(val start: Long, val end: Long)
    private data class Attempt(val id: String, val dir: File)
    private val registered = ConcurrentHashMap<Key, Attempt>()

    fun inFlightRecordingIds(): Set<String> = registered.values.map { it.id }.toSet() + listOfNotNull(activeId)
    override fun bindSealWindowHold(hold: SealWindowHold) { this.hold = hold }
    fun reasonWhenOff(): ReasonCode? = when {
        !storageStatus.isStorageOk() -> ReasonCode.STORAGE_FULL
        failedReason != null -> failedReason
        interruption.isUnresolved() -> ReasonCode.SCREEN_CAPTURE_FAILED
        else -> null
    }

    override fun start(sink: EmissionSink) {
        synchronized(lifecycleLock) {
            if (!sessionHolder.isSessionLive()) return
            if (worker?.isAlive == true) {
                if (!running.get()) pendingStart = sink
                return
            }
            running.set(true)
            failedReason = null
            outputDirectory.mkdirs()
            val generation = sessionHolder.generation()
            worker = Thread({ runWorker(sink, generation) }, "ScreenSourceEngineWorker").also { it.start() }
        }
    }

    override fun stop() {
        val thread = synchronized(lifecycleLock) {
            running.set(false)
            pendingStart = null
            worker
        }
        if (thread !== Thread.currentThread()) {
            thread?.interrupt()
            thread?.join(stopJoinTimeoutMs)
        }
    }

    override fun condition() = SourceCondition(
        desiredOn = true,
        running = running.get() && sessionHolder.isSessionLive(),
        available = storageStatus.isStorageOk(),
        needsAttention = reasonWhenOff() != null,
        paused = false,
        silenced = SilencedFact.UNKNOWN,
    )

    override fun open(payload: SegmentPayload): InputStream =
        File(checkNotNull(registered[Key(payload.captureStartEpochMs, payload.captureEndEpochMs)]).dir,
            ScreenConstants.PAYLOAD_NAME).inputStream()

    override fun release(payload: SegmentPayload) {
        registered.remove(Key(payload.captureStartEpochMs, payload.captureEndEpochMs))?.dir?.deleteRecursively()
    }

    override fun discardUncommitted(payload: SegmentPayload): Boolean {
        // Keep the independently held attempt for recovery; a watermark is not durable custody.
        interruption.markUnresolved()
        registered.remove(Key(payload.captureStartEpochMs, payload.captureEndEpochMs))
        return false
    }

    private fun runWorker(sink: EmissionSink, generation: Long) {
        var floor = false
        var failed = false
        try {
            while (running.get() && sessionHolder.isSessionLive() && sessionHolder.generation() == generation) {
                if (!storageStatus.isStorageOk()) { floor = true; break }
                val display = sessionHolder.currentVirtualDisplay.get() ?: break
                val geometry = sessionHolder.currentGeometry.get() ?: break
                val end = windowStart(nowProvider()) + ScreenConstants.WINDOW_MS
                recordPortion(sink, display, geometry, end)
                if (!storageStatus.isStorageOk()) { floor = true; break }
            }
        } catch (_: InterruptedException) {
            // The portion's finally block has already stopped, detached and adopted its prefix.
        } catch (t: Throwable) {
            diag("screen event=failed type=${t.javaClass.simpleName}")
            interruption.markUnresolved()
            failed = true
        } finally {
            running.set(false)
            activeId = null
            if ((floor || failed) && sessionHolder.stopIfGeneration(generation)) {
                failedReason = if (floor) ReasonCode.STORAGE_FULL else ReasonCode.SCREEN_CAPTURE_FAILED
                if (floor) onFloorTripped?.invoke() else onFailed?.invoke()
            }
            synchronized(lifecycleLock) {
                if (worker === Thread.currentThread()) worker = null
                val next = pendingStart
                pendingStart = null
                if (next != null && sessionHolder.isSessionLive()) start(next)
            }
        }
    }

    private fun recordPortion(sink: EmissionSink, display: ScreenVirtualDisplay, geometry: ScreenGeometry, windowEnd: Long) {
        val start = nowProvider()
        val grid = windowStart(start)
        val zone = zoneProvider()
        val id = "rec-${UUID.randomUUID()}"
        val dir = File(outputDirectory, id)
        check(dir.mkdirs())
        val raw = File(dir, "capture.h264")
        val index = File(dir, "samples.idx")
        val record = File(dir, "record.txt")
        val mp4 = File(dir, ScreenConstants.PAYLOAD_NAME)
        var info = ScreenRecordInfo(id, start, zone.id, zone.rules.getOffset(Instant.ofEpochMilli(start)).totalSeconds,
            grid, windowEnd, geometry.width, geometry.height)
        check(writeAndForceRecordTxt(record, info)) { "screen metadata unavailable" }
        activeId = id
        val coordinator = hold
        var held = false
        var encoder: ScreenEncoder? = null
        val firstFrame = java.util.concurrent.atomic.AtomicReference<Long?>(null)
        var wasInterrupted = false
        var recordingFailure: Throwable? = null
        try {
            coordinator?.hold(MAIN_STREAM, grid)
            held = coordinator != null
            if (!running.get()) throw InterruptedException()
            encoder = encoderFactory.create(geometry.width, geometry.height, ScreenConstants.BIT_RATE, ScreenConstants.FRAME_RATE)
            encoder.start(raw, index) { first ->
                firstFrame.set(first)
                check(writeAndForceRecordTxt(record, info.copy(firstFrameEpochMs = first)))
            }
            display.setSurface(encoder.inputSurface())
            while (running.get() && sessionHolder.isSessionLive() && nowProvider() < windowEnd &&
                storageStatus.isStorageOk() && sessionHolder.currentGeometry.get() == geometry && !encoder.sizeLimitReached()) {
                encoder.failure()?.let { throw it }
                sleeper(minOf(250L, windowEnd - nowProvider()).coerceAtLeast(1L))
            }
        } catch (_: InterruptedException) {
            wasInterrupted = true
        } catch (t: Throwable) {
            recordingFailure = t
        } finally {
            // Interrupts must not prevent fsync, draining or remuxing a useful prefix.
            wasInterrupted = Thread.interrupted() || wasInterrupted
            runCatching { display.setSurface(null) }
            val stopError = runCatching { encoder?.stop() }.exceptionOrNull()
            val drainError = encoder?.failure()
            runCatching { encoder?.release() }
            try {
                val end = minOf(windowEnd, nowProvider())
                info = info.copy(firstFrameEpochMs = firstFrame.get(), openEndEpochMs = end)
                check(writeAndForceRecordTxt(record, info))
                if (firstFrame.get() != null && raw.length() > 0L) {
                    val result = remuxer.remux(dir, raw, index, record, mp4)
                    check(result is ScreenRemuxResult.Success && result.sampleDurationMs > 0 && result.outputBytes > 0) {
                        "screen finalization failed"
                    }
                    info = info.copy(sampleDurationMs = result.sampleDurationMs)
                    check(writeAndForceRecordTxt(record, info))
                    val bounds = info.captureBounds(result.sampleDurationMs)
                    check(bounds.second > bounds.first)
                    registered[Key(bounds.first, bounds.second)] = Attempt(id, dir)
                    val emission = SourceEmission(ScreenConstants.SOURCE_ID, MAIN_STREAM, SourceKind.OBSERVER,
                        bounds.first, bounds.second,
                        listOf(PayloadRef(ScreenConstants.PAYLOAD_NAME, ScreenConstants.MEDIA_TYPE, result.outputBytes, null)),
                        mapOf("zoneId" to info.zoneId, "utcOffsetSeconds" to info.utcOffsetSeconds.toString()), emptyList(), independentTimeline = true)
                    if (coordinator != null) {
                        coordinator.releaseAfterFeed(emission)
                        held = false
                    } else sink.emit(emission)
                } else if (raw.length() == 0L && recordingFailure == null && stopError == null && drainError == null) {
                    dir.deleteRecursively()
                } else if (raw.length() > 0L) {
                    error("screen has bytes without a captured frame")
                }
                recordingFailure?.let { throw it }
                stopError?.let { throw it }
                drainError?.let { throw it }
            } finally {
                if (held) coordinator?.release(MAIN_STREAM, grid)
                activeId = null
            }
        }
        if (wasInterrupted) throw InterruptedException()
    }

    companion object {
        fun windowStart(epochMs: Long): Long = Math.floorDiv(epochMs, ScreenConstants.WINDOW_MS) * ScreenConstants.WINDOW_MS
        fun writeAndForceRecordTxt(file: File, info: ScreenRecordInfo): Boolean {
            val temp = File(file.parentFile, "record.tmp")
            return try {
                FileOutputStream(temp).use { it.write(info.serialize().toByteArray(Charsets.UTF_8)); it.fd.sync() }
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                FileChannel.open(file.parentFile!!.toPath(), StandardOpenOption.READ).use { it.force(true) }
                true
            } catch (_: Exception) { temp.delete(); false }
        }
    }
}
