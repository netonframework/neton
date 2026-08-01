package neton.jobs.internal

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 5 段 cron 解析与下次触发时间。全部按 UTC。
 *
 * 定时任务的调度时刻由这里决定，跑错一分钟就是业务事故，但此前它一行测试都没有。
 */
class CronParserTest {

    /** UTC 时间 → epoch millis */
    private fun utc(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 0,
        minute: Int = 0,
    ): Long = LocalDateTime(year, month, day, hour, minute).toInstant(TimeZone.UTC).toEpochMilliseconds()

    private fun next(expr: String, from: Long) = CronParser.nextFireTime(expr, from)

    // ---- 表达式校验 ----

    @Test
    fun requiresExactlyFiveFields() {
        assertFailsWith<IllegalArgumentException> { CronParser.validate("* * * *") }
        assertFailsWith<IllegalArgumentException> { CronParser.validate("* * * * * *") }
        CronParser.validate("* * * * *")
    }

    @Test
    fun acceptsAllSupportedSyntax() {
        CronParser.validate("*/5 * * * *")      // 步长
        CronParser.validate("0 9-18 * * 1-5")   // 范围
        CronParser.validate("0,15,30,45 * * * *") // 列表
        CronParser.validate("0 0-23/6 * * *")   // 范围 + 步长
        CronParser.validate("30 2 1 1 0")       // 全固定值
    }

    @Test
    fun rejectsOutOfBoundsValues() {
        assertFailsWith<IllegalArgumentException> { CronParser.validate("60 * * * *") }   // 分钟最大 59
        assertFailsWith<IllegalArgumentException> { CronParser.validate("* 24 * * *") }   // 小时最大 23
        assertFailsWith<IllegalArgumentException> { CronParser.validate("* * 0 * *") }    // 日最小 1
        assertFailsWith<IllegalArgumentException> { CronParser.validate("* * * 13 *") }   // 月最大 12
        assertFailsWith<IllegalArgumentException> { CronParser.validate("* * * * 7") }    // 周最大 6
    }

    @Test
    fun rejectsMalformedParts() {
        assertFailsWith<IllegalArgumentException> { CronParser.validate("abc * * * *") }
        assertFailsWith<IllegalArgumentException> { CronParser.validate("*/0 * * * *") }  // 步长必须 > 0
        assertFailsWith<IllegalArgumentException> { CronParser.validate("*/x * * * *") }
        assertFailsWith<IllegalArgumentException> { CronParser.validate("30-10 * * * *") } // start > end
        assertFailsWith<IllegalArgumentException> { CronParser.validate("1-2-3 * * * *") }
    }

    // ---- 下次触发时间 ----

    @Test
    fun everyMinuteFiresAtNextMinuteWithSecondsZeroed() {
        // 12:30:45 → 12:31:00
        val from = utc(2026, 3, 10, 12, 30) + 45_000
        assertEquals(utc(2026, 3, 10, 12, 31), next("* * * * *", from))
    }

    @Test
    fun dailyTimeFiresLaterSameDay() {
        val from = utc(2026, 3, 10, 1, 0)
        assertEquals(utc(2026, 3, 10, 2, 30), next("30 2 * * *", from))
    }

    @Test
    fun dailyTimeRollsToNextDayWhenAlreadyPassed() {
        val from = utc(2026, 3, 10, 3, 0)
        assertEquals(utc(2026, 3, 11, 2, 30), next("30 2 * * *", from))
    }

    @Test
    fun stepMinutesPickNextMultiple() {
        // */15 → :00 :15 :30 :45
        assertEquals(utc(2026, 3, 10, 12, 15), next("*/15 * * * *", utc(2026, 3, 10, 12, 1)))
        assertEquals(utc(2026, 3, 10, 13, 0), next("*/15 * * * *", utc(2026, 3, 10, 12, 46)))
    }

    @Test
    fun listPicksNearestListedValue() {
        assertEquals(utc(2026, 3, 10, 12, 30), next("0,30 * * * *", utc(2026, 3, 10, 12, 5)))
        assertEquals(utc(2026, 3, 10, 13, 0), next("0,30 * * * *", utc(2026, 3, 10, 12, 31)))
    }

    @Test
    fun monthlyRollsIntoNextMonth() {
        // 每月 1 号 00:00
        assertEquals(utc(2026, 4, 1), next("0 0 1 * *", utc(2026, 3, 10, 12, 0)))
    }

    @Test
    fun yearRolloverWorks() {
        assertEquals(utc(2027, 1, 1), next("0 0 1 1 *", utc(2026, 12, 31, 23, 59)))
    }

    @Test
    fun leapDayIsFoundInLeapYear() {
        // 2028 是闰年
        assertEquals(utc(2028, 2, 29), next("0 0 29 2 *", utc(2028, 1, 1)))
    }

    @Test
    fun leapDaySkipsNonLeapYears() {
        // 2026/2027 非闰年 → 下一次是 2028-02-29
        assertEquals(utc(2028, 2, 29), next("0 0 29 2 *", utc(2026, 3, 1)))
    }

    @Test
    fun impossibleDateReturnsMinusOne() {
        // 2 月 30 号永远不存在，扫描窗口耗尽后返回 -1（而不是死循环）
        assertEquals(-1L, next("0 0 30 2 *", utc(2026, 1, 1)))
    }

    @Test
    fun dayOfMonthAndDayOfWeekUseAndSemantics() {
        // 2026-03-10 是周二。要求「1 号且周日」：2026-11-01 是周日
        val result = next("0 0 1 * 0", utc(2026, 3, 10))
        assertEquals(utc(2026, 11, 1), result)
    }

    @Test
    fun weekdayOnlyScheduleSkipsWeekend() {
        // 周一到周五 09:00；2026-03-14 是周六 → 下一次是周一 03-16
        assertEquals(utc(2026, 3, 16, 9, 0), next("0 9 * * 1-5", utc(2026, 3, 14, 12, 0)))
    }

    @Test
    fun resultIsAlwaysStrictlyAfterTheInputInstant() {
        // 正好落在触发点上时，必须给出下一个触发点，而不是原地返回（否则调度器会空转重复执行）
        val exactly = utc(2026, 3, 10, 2, 30)
        val result = next("30 2 * * *", exactly)
        assertTrue(result > exactly, "expected strictly later fire time, got $result for $exactly")
        assertEquals(utc(2026, 3, 11, 2, 30), result)
    }
}
