package com.pocketnode.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommunityStoreTest {
    private val s = CommunityStore(JdbcSqlDb(":memory:"))
    private val family = Community("c1", "Family", setOf("notes.add", "weather.now"), setOf("p1", "p2"), 3)

    @Test fun savesListsAndRemoves() {
        s.save(family)
        assertEquals(listOf(family), s.list())
        s.remove("c1")
        assertEquals(emptyList(), s.list())
    }

    @Test fun toolsAndStrictestLimitComeFromEveryCommunityTheDeviceIsIn() {
        s.save(family)
        s.save(Community("c2", "Friends", setOf("map.open"), setOf("p2"), 2))
        assertEquals(setOf("notes.add", "weather.now", "map.open"), s.toolsFor("p2"))
        assertEquals(2, s.limitFor("p2")); assertEquals(3, s.limitFor("p1")); assertEquals(0, s.limitFor("p9"))
    }

    @Test fun dailyLimitCountsPerPersonAndResetsNextDay() {
        s.save(family)
        repeat(3) { assertTrue(s.tryUse("p1", 100)) }
        assertFalse(s.tryUse("p1", 100))
        assertTrue(s.tryUse("p2", 100))
        assertTrue(s.tryUse("p1", 101))
        assertEquals(3, s.usedToday("p1", 100))
    }

    @Test fun noLimitMeansUnlimited() {
        s.save(family.copy(perPersonPerDay = 0))
        repeat(50) { assertTrue(s.tryUse("p1", 5)) }
    }

    @Test fun anUnpairedDeviceLeaves() {
        s.save(family); s.forget("p1")
        assertEquals(setOf("p2"), s.list().single().members)
    }

    @Test fun badNamesAndTooManyAreRefused() {
        assertFailsWith<IllegalArgumentException> { s.save(family.copy(name = " ")) }
        repeat(CommunityStore.MAX) { s.save(family.copy(id = "x$it", name = "n$it")) }
        assertFailsWith<IllegalArgumentException> { s.save(family.copy(id = "over", name = "over")) }
    }
}
