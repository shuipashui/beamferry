package com.airferrylite.receiver

/** Detects a sustained low-yield phase for the experimental four-code 60 FPS stream. */
internal class QuadPhaseRecovery(
    private val minimumFrames: Long = 45,
    private val poorQrPerFrame: Double = 0.75,
    private val poorWindowsRequired: Int = 1,
    private val maximumAttempts: Int = 1,
    private val maximumProgress: Double = 0.15,
    private val healthyPayloadBytesPerSecond: Double = 120.0 * 1024.0
) {
    private var lastFrames = 0L
    private var lastHits = 0L
    private var poorWindows = 0

    var attempts: Int = 0
        private set
    var beforeQrPerFrame: Double? = null
        private set
    var afterQrPerFrame: Double? = null
        private set
    private var awaitingAfterSample = false

    fun reset() {
        rebase()
        attempts = 0
        beforeQrPerFrame = null
        afterQrPerFrame = null
        awaitingAfterSample = false
    }

    fun rebase(frames: Long = 0L, hits: Long = 0L) {
        lastFrames = frames
        lastHits = hits
        poorWindows = 0
    }

    fun observe(
        enabled: Boolean,
        frames: Long,
        hits: Long,
        solvedBlocks: Int,
        totalBlocks: Int,
        uniquePayloadBytesPerSecond: Double
    ): Boolean {
        if (!enabled) {
            lastFrames = frames
            lastHits = hits
            poorWindows = 0
            return false
        }
        val frameDelta = (frames - lastFrames).coerceAtLeast(0L)
        val hitDelta = (hits - lastHits).coerceAtLeast(0L)
        lastFrames = frames
        lastHits = hits
        if (frameDelta < minimumFrames) return false
        val qrPerFrame = hitDelta.toDouble() / frameDelta
        if (awaitingAfterSample) {
            afterQrPerFrame = qrPerFrame
            awaitingAfterSample = false
        }
        val progress = if (totalBlocks > 0) solvedBlocks.toDouble() / totalBlocks else 0.0
        if (progress >= maximumProgress || uniquePayloadBytesPerSecond >= healthyPayloadBytesPerSecond) {
            poorWindows = 0
            return false
        }
        if (qrPerFrame < poorQrPerFrame) poorWindows += 1 else poorWindows = 0
        if (poorWindows < poorWindowsRequired || attempts >= maximumAttempts) return false
        poorWindows = 0
        beforeQrPerFrame = qrPerFrame
        afterQrPerFrame = null
        awaitingAfterSample = true
        attempts += 1
        return true
    }
}
