package com.airferrylite.receiver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuadPhaseRecoveryTest {
    @Test
    fun sustainedLowYieldRequestsRephase() {
        val recovery = QuadPhaseRecovery()
        assertFalse(recovery.observe(true, 60, 6, 10, 100, 80_000.0))
        assertTrue(recovery.observe(true, 120, 12, 10, 100, 80_000.0))
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
        assertFalse(recovery.observe(true, 60, 0, 0, 100, 0.0))
        assertTrue(recovery.observe(true, 120, 0, 0, 100, 0.0))
        recovery.rebase(60, 0)
        assertFalse(recovery.observe(true, 120, 0, 0, 100, 0.0))
    }

    @Test
    fun sustainedOpticalStallAllowsBoundedLateRephases() {
        val recovery = QuadPhaseRecovery()
        assertFalse(recovery.observe(true, 60, 0, 0, 100, 0.0))
        assertTrue(recovery.observe(true, 120, 0, 0, 100, 0.0))
        assertFalse(recovery.observeOpticalStall(true, 23, 2_000L, 1))
        assertFalse(recovery.observeOpticalStall(true, 30, 1_799L, 4))
        assertTrue(recovery.observeOpticalStall(true, 30, 2_000L, 4))
        assertTrue(recovery.observeOpticalStall(true, 0, 2_000L, 2))
        assertFalse(recovery.observeOpticalStall(true, 30, 2_000L, 4))
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
        assertFalse(recovery.observe(true, 60, 6, 0, 100, 0.0))
        assertTrue(recovery.observe(true, 120, 12, 0, 100, 0.0))
        recovery.rebase(120, 12)
        assertFalse(recovery.observe(true, 180, 102, 0, 100, 0.0))
        assertTrue(recovery.beforeQrPerFrame == 0.1)
        assertTrue(recovery.afterQrPerFrame == 1.5)
    }

    @Test
    fun oneWeakSlotDoesNotRephaseWhileUniqueSymbolsStillAdvance() {
        val recovery = QuadPhaseRecovery()
        assertFalse(recovery.observeOpticalStall(true, 0, 3_000L, 1))
        assertFalse(recovery.observeOpticalStall(true, 30, 500L, 4))
    }
}
