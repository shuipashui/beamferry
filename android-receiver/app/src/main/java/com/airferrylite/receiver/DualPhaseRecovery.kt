package com.airferrylite.receiver

/** Detects a sustained 60/60 bad phase without reacting to brief optical misses. */
internal class DualPhaseRecovery(
    private val minimumFrames: Long = 30,
    private val poorCompleteRatio: Double = 0.30,
    private val poorWindowsRequired: Int = 3,
    private val maximumAttempts: Int = 3
) {
    private var lastComplete = 0L
    private var lastPartial = 0L
    private var poorWindows = 0

    var attempts: Int = 0
        private set

    fun reset() {
        rebase()
        attempts = 0
    }

    fun rebase() {
        lastComplete = 0L
        lastPartial = 0L
        poorWindows = 0
    }

    fun observe(dualLayout: Boolean, completeFrames: Long, partialFrames: Long): Boolean {
        if (!dualLayout) {
            lastComplete = completeFrames
            lastPartial = partialFrames
            poorWindows = 0
            return false
        }
        val complete = (completeFrames - lastComplete).coerceAtLeast(0L)
        val partial = (partialFrames - lastPartial).coerceAtLeast(0L)
        lastComplete = completeFrames
        lastPartial = partialFrames
        val total = complete + partial
        if (total < minimumFrames) return false
        if (complete.toDouble() / total < poorCompleteRatio) poorWindows += 1 else poorWindows = 0
        if (poorWindows < poorWindowsRequired || attempts >= maximumAttempts) return false
        poorWindows = 0
        attempts += 1
        return true
    }
}
