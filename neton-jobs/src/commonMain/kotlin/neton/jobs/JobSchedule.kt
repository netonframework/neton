package neton.jobs

sealed class JobSchedule {
    data class Cron(val expression: String) : JobSchedule()
    data class FixedRate(val intervalMs: Long, val initialDelayMs: Long = 0) : JobSchedule()
}
