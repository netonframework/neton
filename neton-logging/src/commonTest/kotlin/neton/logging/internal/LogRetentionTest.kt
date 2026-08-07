package neton.logging.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 保留窗口的语义是**最近 N 天、含今天**：N=7 时今天 + 往前 6 个归档，更早的删。
 * 这一条要钉死——「保留 7 天」到底留 7 个还是 8 个文件，不写清楚每个人读法都不一样。
 */
class LogRetentionTest {

    private val listing = listOf(
        "all.log",              // 当前正在写的，永远不该被选中
        "all.log.2026-08-06",   // today-1
        "all.log.2026-08-01",   // today-6，窗口最边缘
        "all.log.2026-07-31",   // today-7，出窗口
        "all.log.2026-07-01",   // 远超期
    )

    @Test
    fun keeps_the_window_and_drops_what_falls_out_of_it() {
        val doomed = LogRetention.selectExpired(
            fileNames = listing,
            baseName = "all.log",
            cutoffKey = 20260801,
            retentionDays = 7,
        )
        assertEquals(listOf("all.log.2026-07-31", "all.log.2026-07-01"), doomed)
    }

    /** 当前文件没有日期后缀，任何情况下都不能进删除列表——删了就等于把正在写的日志砍掉。 */
    @Test
    fun the_live_file_is_never_selected() {
        val doomed = LogRetention.selectExpired(
            fileNames = listOf("all.log"),
            baseName = "all.log",
            cutoffKey = 29991231,
            retentionDays = 7,
        )
        assertEquals(emptyList(), doomed)
    }

    /** 同一天多次归档会有 `.1` `.2`，按日期一起判定。 */
    @Test
    fun indexed_archives_are_judged_by_their_date() {
        val doomed = LogRetention.selectExpired(
            fileNames = listOf("all.log.2026-07-01.1", "all.log.2026-07-01.2", "all.log.2026-08-06.1"),
            baseName = "all.log",
            cutoffKey = 20260801,
            retentionDays = 7,
        )
        assertEquals(listOf("all.log.2026-07-01.1", "all.log.2026-07-01.2"), doomed)
    }

    /** 认不出来的一律不碰：同目录下可能有别人的文件。 */
    @Test
    fun unrecognised_files_are_left_alone() {
        val doomed = LogRetention.selectExpired(
            fileNames = listOf(
                "all.log.2026-07-01",   // 该删
                "error.log.2026-07-01", // 别的 sink
                "all.log.backup",       // 解析不出日期
                "all.log.2026-13-45",   // 月/日越界
                "important.tar.gz",
            ),
            baseName = "all.log",
            cutoffKey = 20260801,
            retentionDays = 7,
        )
        assertEquals(listOf("all.log.2026-07-01"), doomed)
    }

    /** <= 0 关闭清理：取证场景要留全量。 */
    @Test
    fun non_positive_retention_disables_purging() {
        for (days in listOf(0, -1)) {
            assertEquals(
                emptyList(),
                LogRetention.selectExpired(listing, "all.log", 29991231, days),
                "retentionDays=$days 应该关闭清理",
            )
        }
    }

    @Test
    fun date_key_parsing() {
        assertEquals(20260807, LogRetention.archiveDateKey("all.log.2026-08-07", "all.log"))
        assertEquals(20260807, LogRetention.archiveDateKey("all.log.2026-08-07.3", "all.log"))
        assertNull(LogRetention.archiveDateKey("all.log", "all.log"))
        assertNull(LogRetention.archiveDateKey("all.log.2026-8-7", "all.log"))
        assertNull(LogRetention.archiveDateKey("other.log.2026-08-07", "all.log"))
    }
}
