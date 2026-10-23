package dev.qcom.bandmenu.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Cell-Lock page's NR-ARFCN list and its NR lock helpers: every
 *  NR-ARFCN seen with its PCIs, and the band and SCS filled in for one. */
class SeenNrArfcnsTest {

    @Test
    fun mergeKeepsRecentLastAddsPcisAndFillsGaps() {
        val old = listOf(SeenNrArfcn(643334, emptyList(), 78, 30), SeenNrArfcn(529950, listOf(134), band = 41))
        // A reading: serving 152690 first, then neighbours.
        val new = listOf(
            SeenNrArfcn(152690, listOf(5), 28, 15), SeenNrArfcn(529950, listOf(133, 134), scsKhz = 30)
        )
        assertEquals(
            listOf(
                SeenNrArfcn(643334, emptyList(), 78, 30),
                SeenNrArfcn(529950, listOf(134, 133), 41, 30),
                SeenNrArfcn(152690, listOf(5), 28, 15)            // the reading's most important, last
            ).map { it.arfcn },
            mergeSeenNrArfcns(old, new).map { it.arfcn }
        )
        val merged = mergeSeenNrArfcns(old, new).associateBy { it.arfcn }
        // The reading's 133 ranks above its 134, so it goes last (dropped last).
        assertEquals(listOf(134, 133), merged.getValue(529950).pcis)
        assertEquals(41, merged.getValue(529950).band)
        assertEquals(30, merged.getValue(529950).scsKhz)
    }

    @Test
    fun emptyStartTakesTheReading() {
        val new = listOf(SeenNrArfcn(529950, listOf(134), 41, 30))
        assertEquals(new, mergeSeenNrArfcns(emptyList(), new))
    }

    @Test
    fun atMostTenChannelsAndPcisLeastRecentlySeenDropped() {
        // Ten channels seen one by one (100 oldest), each with ten PCIs.
        var seen = emptyList<SeenEarfcn>()
        for (e in 100L..109L) seen = mergeSeenEarfcns(seen, listOf(SeenEarfcn(e, (0..9).toList())))
        // 100 seen again, then a new one: 101, now the least recently seen, goes.
        seen = mergeSeenEarfcns(seen, listOf(SeenEarfcn(100, listOf(3))))
        seen = mergeSeenEarfcns(seen, listOf(SeenEarfcn(200, listOf(1))))
        assertEquals(SEEN_MAX, seen.size)
        assertEquals((102L..109L).toList() + listOf(100L, 200L), seen.map { it.earfcn })
        // 100's PCIs (first reading stored least important first: 9..0): 3
        // moved to the end; an eleventh drops the oldest, 9.
        seen = mergeSeenEarfcns(seen, listOf(SeenEarfcn(100, listOf(42))))
        assertEquals(listOf(8, 7, 6, 5, 4, 2, 1, 0, 3, 42), seen.last().pcis)
    }

    @Test
    fun bandOnlyWhenCertain() {
        // n78 sits inside n77: ambiguous unless DIAG resolved it or the modem has one of them.
        assertNull(nrLockBand(643334, emptyList()))
        assertEquals(78, nrLockBand(643334, listOf(SeenNrArfcn(643334, band = 78))))
        assertEquals(77, nrLockBand(643334, emptyList(), supported = setOf(1, 41, 77)))
        // n28 alone at 156000; 152690 is also in n14.
        assertEquals(28, nrLockBand(156000, emptyList()))
        assertNull(nrLockBand(152690, emptyList()))
    }

    @Test
    fun scsFromServingCellOrBand() {
        assertEquals(30, nrLockScs(643334, emptyList()))       // n77/n78, both TDD
        assertEquals(15, nrLockScs(152690, emptyList()))       // n28 FDD
        assertEquals(120, nrLockScs(2079167, emptyList()))     // FR2
        assertEquals(60, nrLockScs(643334, listOf(SeenNrArfcn(643334, scsKhz = 60))))
        // 529950: n41 (TDD) or n7 (FDD); the more deployed n41 wins, or the modem's own band.
        assertEquals(30, nrLockScs(529950, emptyList()))
        assertEquals(15, nrLockScs(529950, emptyList(), supported = setOf(1, 3, 7)))
        assertNull(nrLockScs(1, emptyList()))
    }

    @Test
    fun earfcnsMergeWithPcisAddedUp() {
        val old = listOf(SeenEarfcn(1300, listOf(10), 3))
        val new = listOf(SeenEarfcn(1300, listOf(11, 10)), SeenEarfcn(100, listOf(200), 1))
        assertEquals(
            listOf(SeenEarfcn(100, listOf(200), 1), SeenEarfcn(1300, listOf(10, 11), 3)),
            mergeSeenEarfcns(old, new)
        )
    }
}
