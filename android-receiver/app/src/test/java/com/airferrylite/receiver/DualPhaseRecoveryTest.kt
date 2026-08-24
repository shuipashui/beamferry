package com.airferrylite.receiver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DualPhaseRecoveryTest {
    @Test
    fun sustainedHalfSpeedRequestsOneRephase() {
        val recovery = DualPhaseRecovery()
        assertFalse(recovery.observe(true, 5, 55))
        assertFalse(recovery.observe(true, 10, 110))
        assertTrue(recovery.observe(true, 15, 165))
    }

    @Test
    fun aHealthyWindowClearsThePoorStreak() {
        val recovery = DualPhaseRecovery()
        assertFalse(recovery.observe(true, 5, 55))
        assertFalse(recovery.observe(true, 45, 75))
        assertFalse(recovery.observe(true, 50, 130))
        assertFalse(recovery.observe(true, 55, 185))
    }

    @Test
    fun attemptsAreBounded() {
        val recovery = DualPhaseRecovery(poorWindowsRequired = 1, maximumAttempts = 2)
        assertTrue(recovery.observe(true, 0, 60))
        assertTrue(recovery.observe(true, 0, 120))
        assertFalse(recovery.observe(true, 0, 180))
    }

    @Test
    fun rebaseKeepsAttemptBudgetAfterAnalyzerCountersReset() {
        val recovery = DualPhaseRecovery(poorWindowsRequired = 1, maximumAttempts = 2)
        assertTrue(recovery.observe(true, 0, 60))
        recovery.rebase()
        assertTrue(recovery.observe(true, 0, 60))
        recovery.rebase()
        assertFalse(recovery.observe(true, 0, 60))
    }
}
