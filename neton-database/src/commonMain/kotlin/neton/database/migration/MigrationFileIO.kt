package neton.database.migration

/**
 * 文件 IO 平台抽象 — 仅 migration engine 使用,不外露。
 *
 * commonMain 仅 expect 声明:
 *   - posixMain (macosArm64 / linuxX64 / linuxArm64) actual: POSIX stat / opendir / read
 *   - mingwX64Main actual: Windows _stat64 / _open / _read(目录遍历占位)
 */
internal expect object MigrationFileIO {
    fun exists(path: String): Boolean
    fun isDirectory(path: String): Boolean
    fun listDir(path: String): List<String>
    fun readText(path: String): String?
}

/** 当前 epoch millis — 写 history.installed_at / 计算 execution_ms 用。 */
internal expect fun migrationCurrentTimeMillis(): Long
