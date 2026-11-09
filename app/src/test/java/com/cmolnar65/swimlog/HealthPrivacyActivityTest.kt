package com.cmolnar65.swimlog

import org.junit.Assert.assertTrue
import org.junit.Test

class HealthPrivacyActivityTest {
    @Test
    fun disclosureExplainsScopeDestinationAndPrivacyLimits() {
        val disclosure = HealthPrivacyActivity.HEALTH_DATA_DISCLOSURE

        assertTrue(disclosure.contains("exercise sessions"))
        assertTrue(disclosure.contains("blood pressure"))
        assertTrue(disclosure.contains("speed, active and total calories"))
        assertTrue(disclosure.contains("walking/running step cadence"))
        assertTrue(disclosure.contains("cycling cadence and power"))
        assertTrue(disclosure.contains("laps and segments"))
        assertTrue(disclosure.contains("requested separately for each workout"))
        assertTrue(disclosure.contains("HTTPS Swim Log account"))
        assertTrue(disclosure.contains("stored encrypted"))
        assertTrue(disclosure.contains("does not write to Health Connect"))
        assertTrue(disclosure.contains("older than 30 days"))
        assertTrue(disclosure.contains("privately for the account"))
        assertTrue(disclosure.contains("never collects your WordPress password"))
    }
}
