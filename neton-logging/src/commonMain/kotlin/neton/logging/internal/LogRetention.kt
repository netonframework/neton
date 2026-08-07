package neton.logging.internal

/**
 * 归档日志的保留判定。
 *
 * 纯函数，不碰文件系统——判据（哪些文件该删）和执行（真去 unlink）分开，前者才测得动。
 *
 * 背景：文件 sink 原本只追加不轮转，生产上 `all.log` 一路涨到 847MB、`error.log` 434MB；
 * 同机的 privchat-server 虽然按天归档，但从不删除，攒到 40.5GB 占了磁盘已用空间的一半。
 * 两边现在用同一套语义：**保留最近 N 天（含今天）**，N 默认 7。
 */
internal object LogRetention {

    const val DEFAULT_RETENTION_DAYS: Int = 7

    /**
     * 从归档文件名里取日期。
     *
     * 命名规则与 privchat-server 对齐：`<basename>.YYYY-MM-DD`，同一天多次轮转追加 `.1` `.2`。
     * 判据用**文件名里的日期**而不是 mtime：归档写完就不再变，但 rsync / cp / 备份还原都会把
     * mtime 刷成当下，那时按 mtime 判断会把早该删的留下来。
     *
     * @return 形如 20260807 的整数（便于比较），无法解析返回 null
     */
    fun archiveDateKey(fileName: String, baseName: String): Int? {
        val prefix = "$baseName."
        if (!fileName.startsWith(prefix)) return null
        val rest = fileName.substring(prefix.length)
        val datePart = rest.substringBefore('.')
        if (datePart.length != 10) return null
        if (datePart[4] != '-' || datePart[7] != '-') return null
        val y = datePart.substring(0, 4).toIntOrNull() ?: return null
        val m = datePart.substring(5, 7).toIntOrNull() ?: return null
        val d = datePart.substring(8, 10).toIntOrNull() ?: return null
        if (m !in 1..12 || d !in 1..31) return null
        return y * 10000 + m * 100 + d
    }

    /**
     * 在 [fileNames] 里挑出该删的归档。
     *
     * - `retentionDays <= 0` 表示关闭清理（取证场景要留全量，不能因为有默认值就把证据删了）
     * - 认不出来的文件一律不碰：同目录下可能有别人的东西，日志清理没有理由删自己不认识的文件
     * - 当前正在写的 `<basename>`（无日期后缀）永远不在结果里
     *
     * @param cutoffKey 保留窗口的第一天，形如 20260801；早于它的归档要删
     */
    fun selectExpired(
        fileNames: List<String>,
        baseName: String,
        cutoffKey: Int,
        retentionDays: Int,
    ): List<String> {
        if (retentionDays <= 0) return emptyList()
        return fileNames.filter { name ->
            val key = archiveDateKey(name, baseName)
            key != null && key < cutoffKey
        }
    }
}
