package com.airferrylite.receiver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuadPhaseRecoveryTest {
    @Test
    fun sustainedLowYieldRequestsRephase() {
        val recovery = QuadPhaseRecovery()
        assertFalse(recovery.observe(true, 60, 48))
        assertFalse(recovery.observe(true, 120, 108))
        assertTrue(recovery.observe(true, 180, 168))
    }

    @Test
    fun healthyWindowClearsPoorStreak() {
        val recovery = QuadPhaseRecovery()
        assertFalse(recovery.observe(true, 60, 48))
        assertFalse(recovery.observe(true, 120, 168))
        assertFalse(recovery.observe(true, 180, 228))
        assertFalse(recovery.observe(true, 240, 288))
    }

    @Test
    fun ordinaryQuadNeverRequestsRephase() {
        val recovery = QuadPhaseRecovery(poorWindowsRequired = 1)
        assertFalse(recovery.observe(false, 60, 0))
    }

    @Test
    fun attemptsAreBoundedAcrossRebases() {
        val recovery = QuadPhaseRecovery(poorWindowsRequired = 1, maximumAttempts = 2)
        assertTrue(recovery.observe(true, 60, 0))
        recovery.rebase()
        assertTrue(recovery.observe(true, 60, 0))
        recovery.rebase()
        assertFalse(recovery.observe(true, 60, 0))
    }
}
