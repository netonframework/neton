package neton.jobs.internal

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * 极简 5 段 cron 解析器。
 *
 * 格式：分(0-59) 时(0-23) 日(1-31) 月(1-12) 周(0-6, 0=周日)
 * 支持：* 固定值 列表(,) 范围(-) 步长(/) 范围+步长
 * 日/周冲突采用 AND 语义。所有时间基于 UTC。
 */
internal object CronParser {

    private const val MAX_SCAN_MINUTES = 370 * 24 * 60 // 370 天

    /**
     * 给定当前时间，计算下一次触发的时间。
     * @param expression 5 段 cron 表达式
     * @param after 从此时间之后开始查找（epoch millis，UTC）
     * @return 下一次触发时间（epoch millis，UTC），找不到则返回 -1
     */
    fun nextFireTime(expression: String, after: Long): Long {
        val fields = parse(expression)
        val startInstant = Instant.fromEpochMilliseconds(after)
        val startDt = startInstant.toLocalDateTime(TimeZone.UTC)

        // 从 after + 1 分钟开始，秒归零
        var year = startDt.year

        var month = startDt.month.ordinal + 1  // Month enum ordinal: 0=JAN → 1-based
        var day = startDt.day
        var hour = startDt.hour
        var minute = startDt.minute + 1

        // 进位
        if (minute >= 60) {
            minute = 0; hour++
        }
        if (hour >= 24) {
            hour = 0; day++
        }

        var scanned = 0
        while (scanned < MAX_SCAN_MINUTES) {
            // 获取当月天数
            val daysInMonth = daysInMonth(year, month)

            // 日溢出 → 进月
            if (day > daysInMonth) {
                day = 1; month++
                if (month > 12) {
                    month = 1; year++
                }
                hour = 0; minute = 0
                continue
            }

            // 检查月
            if (month !in fields.months) {
                month++
                if (month > 12) {
                    month = 1; year++
                }
                day = 1; hour = 0; minute = 0
                continue
            }

            // 检查日 AND 周
            val dayOfWeek = dayOfWeek(year, month, day)
            if (day !in fields.daysOfMonth || dayOfWeek !in fields.daysOfWeek) {
                day++; hour = 0; minute = 0
                scanned += 24 * 60
                continue
            }

            // 检查时
            if (hour !in fields.hours) {
                hour++
                if (hour >= 24) {
                    hour = 0; day++; }
                minute = 0
                scanned += 60
                continue
            }

            // 检查分
            if (minute !in fields.minutes) {
                minute++
                if (minute >= 60) {
                    minute = 0; hour++
                }
                scanned++
                continue
            }

            // 匹配成功
            val dt = LocalDateTime(year, month, day, hour, minute, 0, 0)
            return dt.toInstant(TimeZone.UTC).toEpochMilliseconds()
        }

        return -1 // 370 天内未找到
    }

    /**
     * 校验 cron 表达式是否合法。
     * @throws IllegalArgumentException 不合法时抛出
     */
    fun validate(expression: String) {
        parse(expression)
    }

    // --- 内部实现 ---

    private data class CronFields(
        val minutes: Set<Int>,
        val hours: Set<Int>,
        val daysOfMonth: Set<Int>,
        val months: Set<Int>,
        val daysOfWeek: Set<Int>
    )

    private fun parse(expression: String): CronFields {
        val parts = expression.trim().split("\\s+".toRegex())
        require(parts.size == 5) { "Cron expression must have 5 fields, got ${parts.size}: '$expression'" }

        return CronFields(
            minutes = parseField(parts[0], 0, 59, "minute"),
            hours = parseField(parts[1], 0, 23, "hour"),
            daysOfMonth = parseField(parts[2], 1, 31, "day-of-month"),
            months = parseField(parts[3], 1, 12, "month"),
            daysOfWeek = parseField(parts[4], 0, 6, "day-of-week")
        )
    }

    private fun parseField(field: String, min: Int, max: Int, name: String): Set<Int> {
        val result = mutableSetOf<Int>()
        for (part in field.split(",")) {
            result.addAll(parsePart(part.trim(), min, max, name))
        }
        require(result.isNotEmpty()) { "Cron field '$name' resolved to empty set: '$field'" }
        return result
    }

    private fun parsePart(part: String, min: Int, max: Int, name: String): Set<Int> {
        // */N
        if (part.startsWith("*/")) {
            val step = part.substring(2).toIntOrNull()
                ?: throw IllegalArgumentException("Invalid step in cron field '$name': '$part'")
            require(step > 0) { "Step must be > 0 in cron field '$name': '$part'" }
            return (min..max step step).toSet()
        }

        // *
        if (part == "*") {
            return (min..max).toSet()
        }

        // range with optional step: N-M or N-M/S
        if ("-" in part) {
            val (rangePart, stepPart) = if ("/" in part) {
                val idx = part.indexOf("/")
                part.substring(0, idx) to part.substring(idx + 1)
            } else {
                part to null
            }

            val bounds = rangePart.split("-")
            require(bounds.size == 2) { "Invalid range in cron field '$name': '$part'" }
            val start = bounds[0].toIntOrNull()
                ?: throw IllegalArgumentException("Invalid range start in cron field '$name': '$part'")
            val end = bounds[1].toIntOrNull()
                ?: throw IllegalArgumentException("Invalid range end in cron field '$name': '$part'")
            require(start in min..max && end in min..max) {
                "Range out of bounds ($min-$max) in cron field '$name': '$part'"
            }
            require(start <= end) { "Range start > end in cron field '$name': '$part'" }

            val step = if (stepPart != null) {
                val s = stepPart.toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid step in cron field '$name': '$part'")
                require(s > 0) { "Step must be > 0 in cron field '$name': '$part'" }
                s
            } else 1

            return (start..end step step).toSet()
        }

        // single value
        val value = part.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid value in cron field '$name': '$part'")
        require(value in min..max) { "Value $value out of bounds ($min-$max) in cron field '$name'" }
        return setOf(value)
    }

    private fun daysInMonth(year: Int, month: Int): Int {
        return when (month) {
            1, 3, 5, 7, 8, 10, 12 -> 31
            4, 6, 9, 11 -> 30
            2 -> if (isLeapYear(year)) 29 else 28
            else -> throw IllegalArgumentException("Invalid month: $month")
        }
    }

    private fun isLeapYear(year: Int): Boolean =
        (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)

    /** 返回 0-6，0=周日（与 cron 一致） */
    private fun dayOfWeek(year: Int, month: Int, day: Int): Int {
        val dt = LocalDate(year, month, day)
        return when (dt.dayOfWeek) {
            DayOfWeek.SUNDAY -> 0
            DayOfWeek.MONDAY -> 1
            DayOfWeek.TUESDAY -> 2
            DayOfWeek.WEDNESDAY -> 3
            DayOfWeek.THURSDAY -> 4
            DayOfWeek.FRIDAY -> 5
            DayOfWeek.SATURDAY -> 6
        }
    }
}
