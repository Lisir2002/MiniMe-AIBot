package com.minime.code.feature.settings.presentation

import com.minime.code.feature.agent.data.local.dao.DayCallStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenHeatmapTest {

    private fun bucket(epochHour: Long, input: Long, output: Long, calls: Int = 1) =
        DayCallStats(epochHour, input, output, 0, calls, 0, null, null)

    @Test
    fun epochDay0IsThursday_index3() {
        // 1970-01-01 是周四；周一开始计数 -> 下标 3
        assertEquals(3, hourBucketToDayOfWeek(0))
        assertEquals(3, hourBucketToDayOfWeek(12)) // 同一天的不同小时仍是周四
    }

    @Test
    fun weekdayMappingWrapsCorrectly() {
        // epochDay 0=Thu(3), 1=Fri(4), 2=Sat(5), 3=Sun(6), 4=Mon(0), 5=Tue(1), 6=Wed(2)
        val expected = listOf(3, 4, 5, 6, 0, 1, 2)
        expected.forEachIndexed { day, dow ->
            assertEquals("epochDay $day", dow, hourBucketToDayOfWeek(day * 24L))
        }
    }

    @Test
    fun negativeEpochHourStillMaps() {
        // 1970 前（正常数据不会有，但保证取模非负）：epochDay -1 = 1969-12-31 周三 -> 下标 2
        assertEquals(2, hourBucketToDayOfWeek(-24))
    }

    @Test
    fun hourOfDayMapping() {
        assertEquals(0, hourBucketToHourOfDay(0))
        assertEquals(13, hourBucketToHourOfDay(13))
        assertEquals(23, hourBucketToHourOfDay(23))
        assertEquals(13, hourBucketToHourOfDay(24 + 13))
        assertEquals(13, hourBucketToHourOfDay(7 * 24L + 13))
    }

    @Test
    fun emptyBucketsYields168EmptyCells() {
        val data = buildHeatmap(emptyList())
        assertEquals(168, data.cells.size)
        assertEquals(0L, data.peakTokens)
        assertEquals(0L, data.totalTokens)
        assertFalse(data.hasData)
        assertTrue(data.cells.all { it.tokens == 0L && it.calls == 0 })
    }

    @Test
    fun aggregatesIntoCorrectCell() {
        // epochHour 4+13: epochDay0=Thu(dow3), hour13
        val data = buildHeatmap(listOf(bucket(13, 100, 50, 2)))
        val cell = data.cells[3 * 24 + 13]
        assertEquals(150L, cell.tokens)
        assertEquals(2, cell.calls)
        assertEquals(150L, data.peakTokens)
        assertEquals(150L, data.totalTokens)
    }

    @Test
    fun sameSlotAcrossWeeksAccumulates() {
        // 两个周四 13 点（相隔 7 天）应累加到同一格
        val data = buildHeatmap(
            listOf(
                bucket(13, 100, 0),
                bucket(7 * 24L + 13, 200, 50)
            )
        )
        val cell = data.cells[3 * 24 + 13]
        assertEquals(350L, cell.tokens)
        assertEquals(2, cell.calls)
        assertEquals(350L, data.peakTokens)
        assertEquals(350L, data.totalTokens)
        // 其余格子为空
        assertEquals(167, data.cells.count { it.tokens == 0L })
    }

    @Test
    fun peakIsMaxSingleCellNotSum() {
        val data = buildHeatmap(
            listOf(
                bucket(0, 900, 0),   // Mon 00:00 -> epochDay0 是 dow3, hour0
                bucket(24, 100, 0)   // epochDay1 -> dow4
            )
        )
        assertEquals(900L, data.peakTokens)
        assertEquals(1000L, data.totalTokens)
    }

    @Test
    fun levelUsesLogScaleAndBounds() {
        assertEquals(0, heatmapLevel(0, 1000))
        assertEquals(0, heatmapLevel(50, 0))
        assertEquals(4, heatmapLevel(1000, 1000))
        val l1 = heatmapLevel(1, 1_000_000)
        val l2 = heatmapLevel(1000, 1_000_000)
        val l3 = heatmapLevel(1_000_000, 1_000_000)
        assertTrue("log scale should be monotonic: $l1 $l2 $l3", l1 <= l2 && l2 <= l3)
        assertTrue("small usage still visible", l1 >= 1)
        assertTrue("peak maps to top level", l3 == 4)
    }
}
