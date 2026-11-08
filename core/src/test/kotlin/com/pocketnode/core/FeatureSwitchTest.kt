package com.pocketnode.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureSwitchTest {
    @Test fun everythingIsOnByDefault() {
        val s = FeatureSwitch()
        assertTrue(FeatureSwitch.ALL.keys.all { s.enabled(it) })
        assertTrue(s.toolEnabled("weather.get"))
    }

    @Test fun aFeatureCanBeSwitchedOffAndIdsIgnoreCaseAndSeparators() {
        val s = FeatureSwitch("Voice_Call; voice_talk bank_alerts")
        assertFalse(s.enabled("voice_call")); assertFalse(s.enabled("voice_talk")); assertTrue(s.enabled("agents"))
    }

    @Test fun toolsGoOffByNameOrByStarPrefix() {
        val s = FeatureSwitch(tools = "weather.get, notes.*")
        assertFalse(s.toolEnabled("weather.get")); assertTrue(s.toolEnabled("weather.forecast"))
        assertFalse(s.toolEnabled("notes.add")); assertFalse(s.toolEnabled("notes.list")); assertTrue(s.toolEnabled("phone.status"))
    }

    @Test fun toolsOfAnOffFeatureGoOffWithIt() {
        val s = FeatureSwitch("agents,bank_alerts")
        assertFalse(s.toolEnabled("agent.create")); assertFalse(s.toolEnabled("bank.recent")); assertTrue(s.toolEnabled("clock.now"))
    }

    @Test fun aTypoIsReported() {
        assertEquals(listOf("voice_cal"), FeatureSwitch("voice_cal,agents").unknown)
        assertTrue(FeatureSwitch("voice_call").unknown.isEmpty())
    }
}
