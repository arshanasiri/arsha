package com.example.callrecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LibraryTest {
    private fun rec(n: String, num: String, name: String = "", t: Long, dur: Long = 1000, dir: Int = 1, size: Long = 100) =
        Rec("/x/$n.wav", "$n.wav", t, dur, size, num, name, dir)

    @Test fun sameNumberDifferentFormatsShareKey() {
        val k = Library.contactKey("+98 912 123 4567")
        assertEquals(k, Library.contactKey("09121234567"))
        assertEquals(k, Library.contactKey("0098-912-123-4567"))
        assertNotEquals(k, Library.contactKey("09121234568"))
        assertEquals("", Library.contactKey(""))
    }

    @Test fun groupingMergesFormatsAndPicksName() {
        val l = listOf(
            rec("a", "+989121234567", "علی", 3), rec("b", "09121234567", "", 2),
            rec("c", "02188776655", "", 1), rec("d", "", "", 0),
        )
        val g = Library.groupByContact(l, SortMode.NEWEST)
        assertEquals(3, g.size)
        assertEquals("علی", g[0].title)
        assertEquals(2, g[0].items.size)
        assertEquals("ناشناس", g.last().title)
    }

    @Test fun filterByDirectionAndQuery() {
        val l = listOf(rec("a", "09121234567", "Ali", 3, dir = 1), rec("b", "09350000000", "", 2, dir = 2))
        assertEquals(1, Library.filter(l, "", DirFilter.OUTGOING).size)
        assertEquals(1, Library.filter(l, "ali", DirFilter.ALL).size)
        assertEquals(1, Library.filter(l, "9350000", DirFilter.ALL).size)
        assertEquals(0, Library.filter(l, "zzz", DirFilter.ALL).size)
    }

    @Test fun sorting() {
        val l = listOf(rec("a", "1111", t = 1, dur = 50, size = 5), rec("b", "2222", t = 3, dur = 10, size = 9), rec("c", "3333", t = 2, dur = 70, size = 1))
        assertEquals("b", Library.sort(l, SortMode.NEWEST)[0].fileName.removeSuffix(".wav"))
        assertEquals("a", Library.sort(l, SortMode.OLDEST)[0].fileName.removeSuffix(".wav"))
        assertEquals("c", Library.sort(l, SortMode.LONGEST)[0].fileName.removeSuffix(".wav"))
        assertEquals("b", Library.sort(l, SortMode.LARGEST)[0].fileName.removeSuffix(".wav"))
    }

    @Test fun callLogMatching() {
        // date, durationSec
        val calls = listOf(longArrayOf(1_000_000, 60, 1), longArrayOf(5_000_000, 120, 2))
        assertEquals(1, CallMeta.Matcher.best(calls, 5_010_000, 60_000))
        assertEquals(0, CallMeta.Matcher.best(calls, 1_005_000, 30_000))
        assertEquals(-1, CallMeta.Matcher.best(calls, 9_000_000, 30_000))
    }
}
