package com.airferrylite.receiver

/** Detects a sustained low-yield phase for the experimental four-code 60 FPS stream. */
internal class QuadPhaseRecovery(
    private val minimumFrames: Long = 30,
    private val poorQrPerFrame: Double = 1.25,
    private val poorWindowsRequired: Int = 3,
    private val maximumAttempts: Int = 3
) {
    private var lastFrames = 0L
    private var lastHits = 0L
    private var poorWindows = 0

    var attempts: Int = 0
        private set

    fun reset() {
        rebase()
        attempts = 0
    }

    fun rebase() {
        lastFrames = 0L
        lastHits = 0L
        poorWindows = 0
    }

    fun observe(enabled: Boolean, frames: Long, hits: Long): Boolean {
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
        if (hitDelta.toDouble() / frameDelta < poorQrPerFrame) poorWindows += 1 else poorWindows = 0
        if (poorWindows < poorWindowsRequired || attempts >= maximumAttempts) return false
        poorWindows = 0
        attempts += 1
        return true
    }
}
