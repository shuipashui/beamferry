package com.airferrylite.receiver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuadPhaseRecoveryTest {
    @Test
    fun sustainedLowYieldRequestsRephase() {
        val recovery = QuadPhaseRecovery()
        assertTrue(recovery.observe(true, 60, 30, 10, 100, 80_000.0))
    }

    @Test
    fun healthyWindowClearsPoorStreak() {
        val recovery = QuadPhaseRecovery()
        assertFalse(recovery.observe(true, 60, 60, 10, 100, 80_000.0))
        assertFalse(recovery.observe(true, 120, 90, 12, 100, 80_000.0))
    }

    @Test
    fun ordinaryQuadNeverRequestsRephase() {
        val recovery = QuadPhaseRecovery(poorWindowsRequired = 1)
        assertFalse(recovery.observe(false, 60, 0, 0, 0, 0.0))
    }

    @Test
    fun attemptsAreBoundedAcrossRebases() {
        val recovery = QuadPhaseRecovery()
        assertTrue(recovery.observe(true, 60, 0, 0, 100, 0.0))
        recovery.rebase(60, 0)
        assertFalse(recovery.observe(true, 120, 0, 0, 100, 0.0))
    }

    @Test
    fun healthyThroughputAndLateProgressBlockRephase() {
        val throughput = QuadPhaseRecovery()
        assertFalse(throughput.observe(true, 60, 0, 5, 100, 121.0 * 1024.0))
        val progress = QuadPhaseRecovery()
        assertFalse(progress.observe(true, 60, 0, 15, 100, 0.0))
    }

    @Test
    fun recordsYieldBeforeAndAfterTheSingleRephase() {
        val recovery = QuadPhaseRecovery()
        assertTrue(recovery.observe(true, 60, 30, 0, 100, 0.0))
        recovery.rebase(60, 30)
        assertFalse(recovery.observe(true, 120, 120, 0, 100, 0.0))
        assertTrue(recovery.beforeQrPerFrame == 0.5)
        assertTrue(recovery.afterQrPerFrame == 1.5)
    }
}
