package com.airferrylite.receiver

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicReference

data class DecodedQr(val bytes: ByteArray?, val text: String?)
data class ScanStats(
    val captureFps: Double,
    val analysisFps: Double,
    val validQrFps: Double,
    val droppedFrames: Long,
    val width: Int,
    val height: Int,
    val submittedFrames: Long,
    val multiScans: Long,
    val multiHits: Long,
    val singleHits: Long,
    val averageDecodeMs: Double,
    val workerCount: Int,
    val workerBusy: Int,
    val emptyDecodes: Long,
    val decodeErrors: Long,
    val bufferAllocations: Long,
    val roiMisses: Int,
    val roiTracked: Boolean,
    val multiLayout: Boolean,
    val tileCount: Int,
    val pipelineRecoveries: Long,
    val decodedThisFrame: Int = 0,
    val dualLayout: Boolean = false,
    val quadLayout: Boolean = false,
    val dualCompleteFrames: Long = 0,
    val dualPartialFrames: Long = 0,
    val dualRecoveryScans: Long = 0,
    val dualLeftCropHits: Long = 0,
    val dualRightCropHits: Long = 0,
    val dualAxis: String = "unlocked",
    val dualCacheAvailable: Boolean = false,
    val bootstrapRetryScans: Long = 0,
    val dualGeometry: String = "unlocked",
    val quadFullRefresh60: Boolean = false,
    val quadFrameCounts: List<Long> = List(5) { 0L },
    val quadSlotHits: List<Long> = List(4) { 0L },
    val quadRecoveryScans: Long = 0,
    val quadSlotRecoveryScans: Long = 0,
    val quadStableCacheAvailable: Boolean = false
)

/** Latest-frame zxing-cpp scan on the CameraX analyzer thread. */
class QrFrameAnalyzer(
    private val onDecoded: (DecodedQr) -> Unit = {},
    private val onDecodedBatch: ((List<DecodedQr>) -> Unit)? = null,
    private val onStats: (ScanStats) -> Unit = {}
) : ImageAnalysis.Analyzer {
    @Volatile private var decoder = NativeQrDecoder()
    @Volatile private var tileDecoders = Array(TILE_WORKERS) { NativeQrDecoder() }
    @Volatile private var decodeExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var tileExecutor: ExecutorService = Executors.newFixedThreadPool(TILE_WORKERS)
    private val workerBusy = AtomicInteger(0)
    private val skipUntilRecover = AtomicBoolean(false)
    private val analysisIdle = AtomicBoolean(false)
    private val recoverRequested = AtomicBoolean(false)
    private val pipelineRecoveries = AtomicLong(0)
    private val lastImageTimestamp = AtomicLong(0)
    private val staleTimestampFrames = AtomicInteger(0)
    private val lumaLock = Any()
    @Volatile private var lumaScratch: ByteBuffer? = null
    private val multiLayout = AtomicBoolean(false)
    private val quadStream = AtomicBoolean(false)
    private val quadFullRefresh60 = AtomicBoolean(false)
    private val dualStream = AtomicBoolean(false)
    private val singleLayoutConfirmed = AtomicBoolean(false)
    private val trackedRoi = AtomicReference<ScanRegion?>(null)
    private val trackedTiles = AtomicReference<List<ScanRegion>?>(null)
    private val tileUndercount = AtomicInteger(0)
    private val roiMisses = AtomicInteger(0)
    private val capturedInWindow = AtomicLong(0)
    private val decodedInWindow = AtomicLong(0)
    private val validQrInWindow = AtomicLong(0)
    private val droppedFrames = AtomicLong(0)
    private val submittedFrames = AtomicLong(0)
    private val multiScans = AtomicLong(0)
    private val multiHits = AtomicLong(0)
    private val singleHits = AtomicLong(0)
    private val decodeNanos = AtomicLong(0)
    private val decodeSamples = AtomicLong(0)
    private val emptyDecodes = AtomicLong(0)
    private val decodeErrors = AtomicLong(0)
    private val statsWindowStartedAt = AtomicLong(SystemClock.elapsedRealtime())
    private val decodedThisFrame = AtomicInteger(0)
    private val dualCompleteFrames = AtomicLong(0)
    private val dualPartialFrames = AtomicLong(0)
    private val dualRecoveryScans = AtomicLong(0)
    private val dualRecoveryTick = AtomicInteger(0)
    private val dualLeftCropHits = AtomicLong(0)
    private val dualRightCropHits = AtomicLong(0)
    private val stableDualTiles = AtomicReference<List<ScanRegion>?>(null)
    private val stableDualCacheMisses = AtomicInteger(0)
    private val stableQuadTiles = AtomicReference<List<ScanRegion>?>(null)
    private val quadFrameCounts = AtomicLongArray(5)
    private val quadSlotHits = AtomicLongArray(4)
    private val quadRecoveryTick = AtomicInteger(0)
    private val quadRecoveryScans = AtomicLong(0)
    private val quadSlotRecoveryScans = AtomicLong(0)
    private val quadSlotMissStreak = AtomicIntegerArray(4)
    private val bootstrapRetryTick = AtomicInteger(0)
    private val bootstrapRetryScans = AtomicLong(0)

    override fun analyze(image: ImageProxy) {
        capturedInWindow.incrementAndGet()
        reportStatsIfDue(image.width, image.height)
        if (skipUntilRecover.get()) {
            droppedFrames.incrementAndGet()
            image.close()
            return
        }
        if (analysisIdle.get()) {
            image.close()
            return
        }
        noteImageTimestamp(image.imageInfo.timestamp)
        val snapshot = try {
            captureLuma(image)
        } catch (_: Exception) {
            decodeErrors.incrementAndGet()
            image.close()
            return
        }
        image.close()
        val region = chooseRegion(snapshot.width, snapshot.height)
        val maxSymbols = if (multiLayout.get() || !singleLayoutConfirmed.get()) 4 else 1
        if (maxSymbols > 1) multiScans.incrementAndGet()
        submittedFrames.incrementAndGet()
        val started = System.nanoTime()
        val hits = try {
            decodeExecutor.submit(Callable { decodeFrame(snapshot, region, maxSymbols) })
                .get(FRAME_DECODE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            requestRecover()
            emptyList()
        } catch (_: Exception) {
            decodeErrors.incrementAndGet()
            emptyList()
        }
        decodeNanos.addAndGet(System.nanoTime() - started)
        decodeSamples.incrementAndGet()
        decodedInWindow.incrementAndGet()
        publish(snapshot.width, snapshot.height, region, hits)
    }

    fun close() {
        skipUntilRecover.set(true)
        decodeExecutor.shutdownNow()
        tileExecutor.shutdownNow()
        runCatching { decodeExecutor.awaitTermination(200, TimeUnit.MILLISECONDS) }
        runCatching { tileExecutor.awaitTermination(200, TimeUnit.MILLISECONDS) }
    }

    fun consumeRecoverRequest(): Boolean = recoverRequested.getAndSet(false)

    fun isPaused(): Boolean = skipUntilRecover.get()

    fun setAnalysisIdle(idle: Boolean) {
        analysisIdle.set(idle)
    }

    fun replaceDecoders() {
        decoder = NativeQrDecoder()
        tileDecoders = Array(TILE_WORKERS) { NativeQrDecoder() }
        synchronized(lumaLock) { lumaScratch = null }
    }

    fun recoverPipeline(count: Boolean = false) {
        skipUntilRecover.set(true)
        val oldDecode = decodeExecutor
        val oldTiles = tileExecutor
        decodeExecutor = Executors.newSingleThreadExecutor()
        tileExecutor = Executors.newFixedThreadPool(TILE_WORKERS)
        decoder = NativeQrDecoder()
        tileDecoders = Array(TILE_WORKERS) { NativeQrDecoder() }
        oldDecode.shutdownNow()
        oldTiles.shutdownNow()
        runCatching { oldDecode.awaitTermination(200, TimeUnit.MILLISECONDS) }
        runCatching { oldTiles.awaitTermination(200, TimeUnit.MILLISECONDS) }
        synchronized(lumaLock) { lumaScratch = null }
        analysisIdle.set(false)
        resetSession()
        lastImageTimestamp.set(0)
        staleTimestampFrames.set(0)
        recoverRequested.set(false)
        skipUntilRecover.set(false)
        if (count) pipelineRecoveries.incrementAndGet()
    }

    fun setMultiLayout(enabled: Boolean) {
        if (multiLayout.getAndSet(enabled) != enabled) {
            roiMisses.set(0)
            if (enabled) singleLayoutConfirmed.set(false) else {
                quadStream.set(false)
                quadFullRefresh60.set(false)
                dualStream.set(false)
                trackedRoi.set(null)
                trackedTiles.set(null)
                tileUndercount.set(0)
            }
        }
    }

    fun resetSession() {
        setMultiLayout(false)
        quadStream.set(false)
        quadFullRefresh60.set(false)
        dualStream.set(false)
        singleLayoutConfirmed.set(false)
        trackedRoi.set(null)
        trackedTiles.set(null)
        tileUndercount.set(0)
        roiMisses.set(0)
        droppedFrames.set(0)
        submittedFrames.set(0)
        multiScans.set(0)
        multiHits.set(0)
        singleHits.set(0)
        decodeNanos.set(0)
        decodeSamples.set(0)
        emptyDecodes.set(0)
        decodeErrors.set(0)
        lastImageTimestamp.set(0)
        staleTimestampFrames.set(0)
        recoverRequested.set(false)
        skipUntilRecover.set(false)
        decodedThisFrame.set(0)
        dualCompleteFrames.set(0)
        dualPartialFrames.set(0)
        dualRecoveryScans.set(0)
        dualRecoveryTick.set(0)
        dualLeftCropHits.set(0)
        dualRightCropHits.set(0)
        bootstrapRetryTick.set(0)
        bootstrapRetryScans.set(0)
        stableDualTiles.set(null)
        stableDualCacheMisses.set(0)
        stableQuadTiles.set(null)
        for (index in 0 until 5) quadFrameCounts.set(index, 0)
        for (index in 0 until 4) quadSlotHits.set(index, 0)
        for (index in 0 until 4) quadSlotMissStreak.set(index, 0)
        quadRecoveryTick.set(0)
        quadRecoveryScans.set(0)
        quadSlotRecoveryScans.set(0)
    }

    private fun chooseRegion(width: Int, height: Int): ScanRegion {
        return ScanLayout.activeRegion(trackedRoi.get(), roiMisses.get(), width, height)
    }

    private fun decodeFrame(luma: LumaSnapshot, region: ScanRegion, maxSymbols: Int): List<NativeHit> {
        if (!multiLayout.get()) {
            val cachedPair = stableDualTiles.get().orEmpty()
            if (cachedPair.size == 2) {
                val cachedHits = readCropsParallel(
                    luma,
                    cachedPair.map { ScanLayout.inflateRect(it, 1.35f, luma.width, luma.height) },
                    retryBinarizer = false,
                    maxSymbols = 2,
                    trackDualSlots = true
                )
                if (cachedHits.isNotEmpty()) {
                    stableDualCacheMisses.set(0)
                    bootstrapRetryTick.set(0)
                    return cachedHits
                }
                if (stableDualCacheMisses.incrementAndGet() >= STABLE_CACHE_MISS_LIMIT) {
                    stableDualTiles.set(null)
                    stableDualCacheMisses.set(0)
                }
            }
            val retryBootstrap = bootstrapRetryTick.incrementAndGet() >= BOOTSTRAP_RETRY_INTERVAL
            if (retryBootstrap) {
                bootstrapRetryTick.set(0)
                bootstrapRetryScans.incrementAndGet()
            }
            val primary = decoder.read(luma, region, maxSymbols, retryBinarizer = retryBootstrap)
            if (primary.isNotEmpty() || maxSymbols <= 1 || singleLayoutConfirmed.get() || quadStream.get()) {
                if (primary.isNotEmpty()) bootstrapRetryTick.set(0)
                return primary
            }
            val merged = mutableListOf<NativeHit>()
            val seen = mutableSetOf<String>()
            fun add(hits: List<NativeHit>) {
                for (hit in hits) {
                    val key = QrPayload.frameKey(QrPayload.bytesFrom(hit.bytes, hit.text)) ?: continue
                    if (seen.add(key)) merged += hit
                }
            }
            // Four overlapping coverage tiles are both cheaper than repeated full-frame
            // max4 and able to bootstrap row, column and diagonal pairs alike.
            add(
                readCropsParallel(
                    luma,
                    ScanLayout.coverageQuadrants(luma.width, luma.height),
                    retryBinarizer = false,
                    maxSymbols = 2
                )
            )
            return if (merged.isEmpty()) primary else merged
        }
        val merged = mutableListOf<NativeHit>()
        val seen = mutableSetOf<String>()
        fun add(hits: List<NativeHit>) {
            for (hit in hits) {
                val key = QrPayload.frameKey(QrPayload.bytesFrom(hit.bytes, hit.text)) ?: continue
                if (seen.add(key)) merged += hit
            }
        }
        val activeTiles = trackedTiles.get().orEmpty().ifEmpty {
            if (quadFullRefresh60.get()) stableQuadTiles.get().orEmpty() else emptyList()
        }
        val previousTiles = activeTiles.map {
            ScanLayout.clampRect(it, luma.width, luma.height)
        }
        val lockedQuad = quadStream.get() && previousTiles.size >= 4
        val lockedDual = dualStream.get() && !quadStream.get()
        if (previousTiles.isNotEmpty()) {
            val crops = if (lockedDual && previousTiles.size == 2) {
                previousTiles.map { ScanLayout.inflateRect(it, 1.22f, luma.width, luma.height) }
            } else {
                previousTiles
            }
            val scanCrops = if (lockedDual && previousTiles.size == 2) {
                crops
            } else {
                crops.filter { !tileCovered(it, merged, previousTiles) }
            }
            add(
                readCropsParallel(
                    luma,
                    scanCrops,
                    retryBinarizer = false,
                    maxSymbols = if (lockedDual) 2 else 1,
                    trackDualSlots = lockedDual,
                    recoverQuadSlots = lockedQuad && quadFullRefresh60.get()
                )
            )
        }
        // Dual 格 2 漏 1 再跑 1440px max4 会掉到 ~28 FPS（0.8.35 / 0.8.90）。四码未锁满格时仍用中心 max4。
        if (!lockedQuad && !lockedDual && previousTiles.size in 2..3 && transferCount(merged) < 2) {
            add(decoder.read(luma, ScanLayout.centerSquare(luma.width, luma.height), 4))
        }
        if (transferCount(merged) >= 4) return merged
        if (previousTiles.size >= 4 && transferCount(merged) >= 3) return merged
        if (lockedQuad) {
            val count = transferCount(merged)
            if (count > 0) quadRecoveryTick.set(0)
            val recoverNow = count == 0 && (
                !quadFullRefresh60.get() ||
                    quadRecoveryTick.incrementAndGet() >= QUAD_RECOVERY_INTERVAL
                )
            if (recoverNow) {
                quadRecoveryTick.set(0)
                if (quadFullRefresh60.get()) quadRecoveryScans.incrementAndGet()
                val exclusive = ScanLayout.exclusiveQuadrants(region)
                val overlays = ScanLayout.overlappingQuadrants(region)
                val pending = overlays.indices.mapNotNull { index ->
                    overlays[index].takeUnless { tileCovered(exclusive[index], merged, exclusive) }
                }
                add(readCropsParallel(luma, pending.take(TILE_WORKERS), retryBinarizer = false))
            }
            return merged
        }
        if (lockedDual) {
            val count = transferCount(merged)
            if (count >= 2) {
                dualRecoveryTick.set(0)
                return merged
            }
            // Keep the hot path to one parallel two-tile pass. A wider recovery pass
            // runs only periodically, otherwise a one-side miss halves 60 FPS again.
            val recoverNow = previousTiles.size < 2 ||
                dualRecoveryTick.incrementAndGet() >= DUAL_RECOVERY_INTERVAL
            if (recoverNow) {
                dualRecoveryTick.set(0)
                dualRecoveryScans.incrementAndGet()
                val recoveryOrdinal = dualRecoveryScans.get()
                val fromHit = merged.firstOrNull { it.points.size >= 2 }?.let { hit ->
                    val candidates = ScanLayout.siblingCandidatesFromHit(
                        hit.points.map { (x, y) -> (hit.originLeft + x) to (hit.originTop + y) },
                        luma.width,
                        luma.height
                    )
                    // Probe axial and diagonal directions on alternating recovery passes.
                    val offset = ((recoveryOrdinal - 1L).coerceAtLeast(0L) % 2L).toInt() * TILE_WORKERS
                    candidates.drop(offset).take(TILE_WORKERS)
                }
                val cachedAxis = dualAxisLabel(stableDualTiles.get())
                val retry = when {
                    cachedAxis == "vertical" -> ScanLayout.dualVerticalHalves(
                        ScanLayout.centerSquare(luma.width, luma.height)
                    )
                    cachedAxis == "horizontal" -> ScanLayout.dualHalves(
                        ScanLayout.centerSquare(luma.width, luma.height)
                    )
                    fromHit != null && fromHit.isNotEmpty() -> fromHit
                    stableDualTiles.get()?.size == 2 -> stableDualTiles.get().orEmpty()
                    previousTiles.size >= 2 ->
                        ScanLayout.dualHalves(
                            ScanLayout.union(previousTiles[0], previousTiles[1], luma.width, luma.height)
                        )
                    else -> ScanLayout.dualAxisHalves(ScanLayout.centerSquare(luma.width, luma.height))
                }
                add(
                    readCropsParallel(
                        luma,
                        retry,
                        retryBinarizer = false,
                        maxSymbols = 2,
                        trackDualSlots = true
                    )
                )
            }
            return merged
        }
        val exclusive = ScanLayout.exclusiveQuadrants(region)
        val overlays = ScanLayout.overlappingQuadrants(region)
        val pending = overlays.indices.mapNotNull { index ->
            overlays[index].takeUnless { tileCovered(exclusive[index], merged, exclusive) }
        }
        add(readCropsSerial(luma, pending, retryBinarizer = false))
        if (transferCount(merged) >= 4) return merged
        if (previousTiles.size >= 4 && transferCount(merged) >= 3) return merged
        val retries = exclusive.mapNotNull { tile ->
            if (tileCovered(tile, merged, exclusive)) null
            else ScanLayout.inflate(tile, 1.28f, luma.width, luma.height)
        }
        add(readCropsSerial(luma, retries, retryBinarizer = true))
        return merged
    }

    private fun readCropsParallel(
        luma: LumaSnapshot,
        crops: List<ScanRegion>,
        retryBinarizer: Boolean,
        maxSymbols: Int = 1,
        trackDualSlots: Boolean = false,
        recoverQuadSlots: Boolean = false
    ): List<NativeHit> {
        if (crops.isEmpty()) return emptyList()
        val symbols = maxSymbols.coerceIn(1, 4)
        if (crops.size == 1) return tileDecoders[0].read(luma, crops[0], symbols, retryBinarizer)
        val jobs = crops.take(TILE_WORKERS)
        workerBusy.set(jobs.size)
        return try {
            jobs.mapIndexed { index, crop ->
                tileExecutor.submit(Callable {
                    var hits = tileDecoders[index].read(luma, crop, symbols, retryBinarizer)
                    if (recoverQuadSlots) {
                        if (hits.isNotEmpty()) quadSlotMissStreak.set(index, 0)
                        else if (quadSlotMissStreak.incrementAndGet(index) >= QUAD_SLOT_RECOVERY_MISSES) {
                            quadSlotMissStreak.set(index, 0)
                            quadSlotRecoveryScans.incrementAndGet()
                            hits = tileDecoders[index].read(luma, crop, 1, retryBinarizer = true)
                            if (hits.isNotEmpty()) quadSlotMissStreak.set(index, 0)
                        }
                    }
                    hits.also {
                        if (trackDualSlots && hits.any { hit ->
                                QrPayload.isTransfer(QrPayload.bytesFrom(hit.bytes, hit.text))
                            }) {
                            if (index == 0) dualLeftCropHits.incrementAndGet()
                            else if (index == 1) dualRightCropHits.incrementAndGet()
                        }
                    }
                })
            }.flatMap { it.get() }
        } finally {
            workerBusy.set(0)
        }
    }

    private fun readCropsSerial(
        luma: LumaSnapshot,
        crops: List<ScanRegion>,
        retryBinarizer: Boolean
    ): List<NativeHit> {
        if (crops.isEmpty()) return emptyList()
        val hits = mutableListOf<NativeHit>()
        for (crop in crops) {
            hits += decoder.read(luma, crop, 1, retryBinarizer)
            if (transferCount(hits) >= 4) break
        }
        return hits
    }

    private fun captureLuma(image: ImageProxy): LumaSnapshot {
        val plane = image.planes[0]
        val source = plane.buffer.duplicate().apply { rewind() }
        val size = source.remaining()
        val copy = synchronized(lumaLock) {
            val existing = lumaScratch
            if (existing != null && existing.capacity() >= size) {
                existing.clear()
                existing.limit(size)
                existing
            } else {
                ByteBuffer.allocateDirect(size).also { lumaScratch = it }
            }
        }
        copy.put(source)
        copy.position(0)
        copy.limit(size)
        return LumaSnapshot(
            copy.slice(),
            plane.rowStride,
            plane.pixelStride.coerceAtLeast(1),
            image.width,
            image.height
        )
    }

    private fun noteImageTimestamp(timestamp: Long) {
        if (timestamp == 0L) return
        val previous = lastImageTimestamp.getAndSet(timestamp)
        if (previous != 0L && previous == timestamp) {
            if (staleTimestampFrames.incrementAndGet() >= STALE_TIMESTAMP_LIMIT) requestRecover()
        } else {
            staleTimestampFrames.set(0)
        }
    }

    private fun requestRecover() {
        decodeErrors.incrementAndGet()
        recoverRequested.set(true)
        skipUntilRecover.set(true)
    }

    private fun transferCount(hits: List<NativeHit>) =
        hits.count { QrPayload.isTransfer(QrPayload.bytesFrom(it.bytes, it.text)) }

    private fun quadTileGrid(imageWidth: Int, imageHeight: Int): List<ScanRegion> {
        return ScanLayout.exclusiveQuadrants(ScanLayout.centerSquare(imageWidth, imageHeight))
    }

    private fun tileCovered(tile: ScanRegion, hits: List<NativeHit>, candidates: List<ScanRegion>): Boolean {
        if (hits.isEmpty() || candidates.isEmpty()) return false
        for (hit in hits) {
            if (hit.points.isEmpty()) continue
            val cx = (hit.originLeft + hit.points.map { it.first }.average()).toFloat()
            val cy = (hit.originTop + hit.points.map { it.second }.average()).toFloat()
            val owner = ScanLayout.ownerIndex(candidates, cx, cy)
            if (owner < 0) continue
            val owned = candidates[owner]
            if (owned.left == tile.left && owned.top == tile.top && owned.width == tile.width && owned.height == tile.height) {
                return true
            }
        }
        return false
    }

    private fun publish(imageWidth: Int, imageHeight: Int, region: ScanRegion, hits: List<NativeHit>) {
        val transferHits = hits.filter { QrPayload.isTransfer(QrPayload.bytesFrom(it.bytes, it.text)) }
        decodedThisFrame.set(transferHits.size)
        if (transferHits.any { QrPayload.isQuadFullRefresh60(QrPayload.bytesFrom(it.bytes, it.text)) }) {
            quadFullRefresh60.set(true)
        }
        if (quadFullRefresh60.get()) {
            quadFrameCounts.incrementAndGet(transferHits.size.coerceIn(0, 4))
            recordQuadSlotHits(transferHits)
        }
        if (dualStream.get()) {
            if (transferHits.size >= 2) dualCompleteFrames.incrementAndGet()
            else dualPartialFrames.incrementAndGet()
        }
        if (transferHits.isEmpty()) {
            emptyDecodes.incrementAndGet()
            val miss = roiMisses.incrementAndGet()
            val lockedTiles = trackedTiles.get()?.size ?: 0
            val missLimit = when {
                lockedTiles >= 4 -> 6
                quadStream.get() -> 6
                dualStream.get() || lockedTiles >= 2 -> 6
                else -> 2
            }
            if (miss >= missLimit) {
                trackedTiles.set(null)
                tileUndercount.set(0)
            }
            return
        }
        roiMisses.set(0)
        validQrInWindow.addAndGet(transferHits.size.toLong())
        if (transferHits.any { QrPayload.isQuadLayout(QrPayload.bytesFrom(it.bytes, it.text)) }) {
            quadStream.set(true)
            dualStream.set(false)
        } else if (transferHits.any { QrPayload.isDualLayout(QrPayload.bytesFrom(it.bytes, it.text)) }) {
            dualStream.set(true)
            singleLayoutConfirmed.set(false)
        }
        val lockedMulti = transferHits.any { QrPayload.isMultiLayout(QrPayload.bytesFrom(it.bytes, it.text)) } ||
            transferHits.size >= 2
        if (lockedMulti) {
            lockMultiLayout()
            multiHits.addAndGet(transferHits.size.toLong())
        } else {
            singleLayoutConfirmed.set(true)
            singleHits.addAndGet(transferHits.size.toLong())
        }
        rememberRoi(imageWidth, imageHeight, transferHits)
        val decoded = transferHits.map { DecodedQr(QrPayload.bytesFrom(it.bytes, it.text), it.text) }
        if (onDecodedBatch != null) onDecodedBatch.invoke(decoded) else decoded.forEach(onDecoded)
    }

    private fun lockMultiLayout() {
        if (!multiLayout.getAndSet(true)) {
            roiMisses.set(0)
            singleLayoutConfirmed.set(false)
        }
    }

    private fun rememberRoi(imageWidth: Int, imageHeight: Int, hits: List<NativeHit>) {
        val points = ArrayList<Pair<Float, Float>>(hits.size * 4)
        for (hit in hits) {
            for ((x, y) in hit.points) {
                points += (hit.originLeft + x) to (hit.originTop + y)
            }
        }
        val existingRoi = trackedRoi.get()
        val perCode = hits.map { hit ->
            hit.points.map { (x, y) -> (hit.originLeft + x) to (hit.originTop + y) }
        }
        ScanLayout.regionFromPoints(
            points,
            imageWidth,
            imageHeight,
            hits.size,
            coverGrid = multiLayout.get() && hits.size < 4
        )?.let { next ->
            trackedRoi.set(
                when {
                    hits.size >= 4 -> next
                    hits.size >= 3 -> ScanLayout.union(
                        existingRoi ?: ScanLayout.centerSquare(imageWidth, imageHeight),
                        next,
                        imageWidth,
                        imageHeight
                    )
                    multiLayout.get() && hits.size >= 2 -> next
                    dualStream.get() && hits.size == 1 ->
                        ScanLayout.pairBandFromHit(perCode.first(), imageWidth, imageHeight)
                            ?: existingRoi
                            ?: ScanLayout.centerSquare(imageWidth, imageHeight)
                    dualStream.get() -> existingRoi ?: ScanLayout.centerSquare(imageWidth, imageHeight)
                    multiLayout.get() -> existingRoi
                    else -> ScanLayout.centerSquare(imageWidth, imageHeight)
                }
            )
        }
        if (!multiLayout.get()) return
        val previous = trackedTiles.get()
        when {
            quadStream.get() -> {
                tileUndercount.set(0)
                val grid = quadTileGrid(imageWidth, imageHeight)
                val base = previous?.takeIf { it.size >= 4 } ?: grid
                val next = ScanLayout.followContainedHits(base, perCode, imageWidth, imageHeight)
                trackedTiles.set(next)
                if (quadFullRefresh60.get() && next.size >= 4) stableQuadTiles.set(next)
            }
            dualStream.get() -> {
                tileUndercount.set(0)
                val next = ScanLayout.updateDualTiles(
                    stableDualTiles.get().orEmpty(),
                    perCode,
                    imageWidth,
                    imageHeight
                )
                trackedTiles.set(next)
                if (next.size == 2) stableDualTiles.set(next)
            }
            hits.size >= 3 -> {
                tileUndercount.set(0)
                trackedTiles.set(ScanLayout.tilesFromHits(perCode, imageWidth, imageHeight))
            }
            hits.size >= 2 && (previous == null || previous.size < 2) -> {
                tileUndercount.set(0)
                trackedTiles.set(ScanLayout.tilesFromHits(perCode, imageWidth, imageHeight))
            }
            previous != null && previous.size >= 2 && hits.size < 2 -> {
                if (tileUndercount.incrementAndGet() >= TILE_UNDERCOUNT_LIMIT) {
                    trackedTiles.set(null)
                    trackedRoi.set(null)
                    tileUndercount.set(0)
                }
            }
            previous != null && previous.size >= 2 -> {
                tileUndercount.set(0)
            }
            previous != null && previous.isNotEmpty() -> {
                tileUndercount.set(0)
                trackedTiles.set(ScanLayout.followContainedHits(previous, perCode, imageWidth, imageHeight))
            }
            hits.size >= 2 -> {
                tileUndercount.set(0)
                trackedTiles.set(ScanLayout.tilesFromHits(perCode, imageWidth, imageHeight))
            }
        }
    }

    private fun recordQuadSlotHits(hits: List<NativeHit>) {
        val slots = (trackedTiles.get()?.takeIf { it.size >= 4 } ?: stableQuadTiles.get()).orEmpty()
        if (slots.size < 4) return
        for (hit in hits) {
            if (hit.points.isEmpty()) continue
            val cx = (hit.originLeft + hit.points.map { it.first }.average()).toFloat()
            val cy = (hit.originTop + hit.points.map { it.second }.average()).toFloat()
            val owner = ScanLayout.ownerIndex(slots, cx, cy)
            if (owner in 0..3) quadSlotHits.incrementAndGet(owner)
        }
    }

    private fun reportStatsIfDue(width: Int, height: Int) {
        val now = SystemClock.elapsedRealtime()
        val startedAt = statsWindowStartedAt.get()
        val elapsed = now - startedAt
        if (elapsed < STATS_INTERVAL_MS || !statsWindowStartedAt.compareAndSet(startedAt, now)) return
        val captured = capturedInWindow.getAndSet(0)
        val decoded = decodedInWindow.getAndSet(0)
        val validQr = validQrInWindow.getAndSet(0)
        onStats(
            ScanStats(
                captureFps = captured * 1000.0 / elapsed.coerceAtLeast(1),
                analysisFps = decoded * 1000.0 / elapsed.coerceAtLeast(1),
                validQrFps = validQr * 1000.0 / elapsed.coerceAtLeast(1),
                droppedFrames = droppedFrames.get(),
                width = width,
                height = height,
                submittedFrames = submittedFrames.get(),
                multiScans = multiScans.get(),
                multiHits = multiHits.get(),
                singleHits = singleHits.get(),
                averageDecodeMs = decodeNanos.get() / 1_000_000.0 / decodeSamples.get().coerceAtLeast(1),
                workerCount = TILE_WORKERS,
                workerBusy = workerBusy.get(),
                emptyDecodes = emptyDecodes.get(),
                decodeErrors = decodeErrors.get(),
                bufferAllocations = 0,
                roiMisses = roiMisses.get(),
                roiTracked = trackedRoi.get() != null && roiMisses.get() < ScanLayout.ROI_MISS_LIMIT,
                multiLayout = multiLayout.get(),
                tileCount = trackedTiles.get()?.size ?: 0,
                pipelineRecoveries = pipelineRecoveries.get(),
                decodedThisFrame = decodedThisFrame.get(),
                dualLayout = dualStream.get(),
                quadLayout = quadStream.get(),
                dualCompleteFrames = dualCompleteFrames.get(),
                dualPartialFrames = dualPartialFrames.get(),
                dualRecoveryScans = dualRecoveryScans.get(),
                dualLeftCropHits = dualLeftCropHits.get(),
                dualRightCropHits = dualRightCropHits.get(),
                dualAxis = dualAxisLabel(trackedTiles.get()),
                dualCacheAvailable = stableDualTiles.get()?.size == 2,
                bootstrapRetryScans = bootstrapRetryScans.get(),
                dualGeometry = dualGeometryLabel(trackedTiles.get()),
                quadFullRefresh60 = quadFullRefresh60.get(),
                quadFrameCounts = List(5) { quadFrameCounts.get(it) },
                quadSlotHits = List(4) { quadSlotHits.get(it) },
                quadRecoveryScans = quadRecoveryScans.get(),
                quadSlotRecoveryScans = quadSlotRecoveryScans.get(),
                quadStableCacheAvailable = (stableQuadTiles.get()?.size ?: 0) >= 4
            )
        )
    }

    private fun dualAxisLabel(tiles: List<ScanRegion>?): String {
        if (tiles == null || tiles.size < 2) return "unlocked"
        val first = tiles[0]
        val second = tiles[1]
        val dx = kotlin.math.abs((first.left + first.width / 2) - (second.left + second.width / 2))
        val dy = kotlin.math.abs((first.top + first.height / 2) - (second.top + second.height / 2))
        return if (dx >= dy) "horizontal" else "vertical"
    }

    private fun dualGeometryLabel(tiles: List<ScanRegion>?): String {
        if (tiles == null || tiles.size < 2) return "unlocked"
        val first = tiles[0]
        val second = tiles[1]
        val dx = kotlin.math.abs((first.left + first.width / 2) - (second.left + second.width / 2))
        val dy = kotlin.math.abs((first.top + first.height / 2) - (second.top + second.height / 2))
        val width = (first.width + second.width) / 2
        val height = (first.height + second.height) / 2
        return when {
            dx > width * 0.55f && dy > height * 0.55f -> "diagonal"
            dx >= dy -> "row"
            else -> "column"
        }
    }

    companion object {
        private const val STATS_INTERVAL_MS = 1000L
        private const val TILE_WORKERS = 4
        private const val FRAME_DECODE_TIMEOUT_MS = 400L
        private const val STALE_TIMESTAMP_LIMIT = 12
        private const val TILE_UNDERCOUNT_LIMIT = 3
        private const val DUAL_RECOVERY_INTERVAL = 8
        private const val QUAD_RECOVERY_INTERVAL = 12
        private const val QUAD_SLOT_RECOVERY_MISSES = 12
        private const val BOOTSTRAP_RETRY_INTERVAL = 8
        private const val STABLE_CACHE_MISS_LIMIT = 3
    }
}
