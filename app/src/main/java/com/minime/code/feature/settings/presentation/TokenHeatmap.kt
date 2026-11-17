package com.minime.code.feature.settings.presentation

import com.minime.code.feature.agent.data.local.dao.DayCallStats
import kotlin.math.ln

/**
 * 活跃时段热力图的一格：星期 [dayOfWeek]（0=周一 … 6=周日）× 小时 [hour]（0..23）。
 * [tokens] 为该时段输入+输出 token 之和（与概览「总消耗」同口径，不含缓存读取），
 * [calls] 为该时段调用次数。
 */
data class HeatmapCell(
    val dayOfWeek: Int,
    val hour: Int,
    val tokens: Long,
    val calls: Int
)

/** 热力图聚合结果：[cells] 恒为 7*24 个格子（含空格），[peakTokens] 为单格最大消耗，[totalTokens] 为周期总消耗。 */
data class TokenHeatmapData(
    val cells: List<HeatmapCell>,
    val peakTokens: Long,
    val totalTokens: Long
) {
    val hasData: Boolean get() = peakTokens > 0
}

/**
 * 把 epoch 小时桶换算成星期序号。
 * epoch 第 0 天（1970-01-01）是周四，对应「周一=0」下标 3。
 * 故 dowIndex = (epochDay + 3) mod 7，用 floorDiv/mod 保证负数也正确。
 */
fun hourBucketToDayOfWeek(hourBucket: Long): Int {
    val epochDay = Math.floorDiv(hourBucket, 24L)
    return (((epochDay + 3) % 7 + 7) % 7).toInt()
}

/** 把 epoch 小时桶换算成当天小时（0..23）。 */
fun hourBucketToHourOfDay(hourBucket: Long): Int =
    ((hourBucket % 24) + 24).toInt() % 24

/**
 * 把按小时聚合的统计映射为 7×24 热力图：跨周的同名时段会累加。
 * 输入 [buckets] 的 day 字段是 epoch 小时桶序号（见 LlmCallRecordDao.getHourStats）。
 */
fun buildHeatmap(buckets: List<DayCallStats>): TokenHeatmapData {
    val tokenGrid = LongArray(7 * 24)
    val callGrid = IntArray(7 * 24)
    var peak = 0L
    for (bucket in buckets) {
        val dow = hourBucketToDayOfWeek(bucket.day)
        val hour = hourBucketToHourOfDay(bucket.day)
        val index = dow * 24 + hour
        val tokens = bucket.inputTokens + bucket.outputTokens
        tokenGrid[index] += tokens
        callGrid[index] += bucket.calls
        if (tokenGrid[index] > peak) peak = tokenGrid[index]
    }
    val cells = ArrayList<HeatmapCell>(7 * 24)
    for (d in 0..6) {
        for (h in 0..23) {
            val i = d * 24 + h
            cells.add(HeatmapCell(d, h, tokenGrid[i], callGrid[i]))
        }
    }
    return TokenHeatmapData(cells, peak, tokenGrid.sum())
}

/**
 * 颜色档位 0..4：0=空格；其余用对数刻度，避免峰值过大时绝大多数格子都停在浅色。
 * tokens<=0 或 peak<=0 一律返回 0。
 */
fun heatmapLevel(tokens: Long, peak: Long): Int {
    if (tokens <= 0 || peak <= 0) return 0
    val ratio = ln(1.0 + tokens) / ln(1.0 + peak)
    return (ratio * 4).toInt().coerceIn(1, 4)
}
