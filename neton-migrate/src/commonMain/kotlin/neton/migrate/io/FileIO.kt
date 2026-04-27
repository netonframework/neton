package neton.migrate.io

/**
 * 文件 IO 平台抽象（v0.1）。
 * commonMain 仅 expect 声明；POSIX 与 Windows 各自 actual。
 */
expect object FileIO {
    fun exists(path: String): Boolean
    fun isDirectory(path: String): Boolean
    fun listDir(path: String): List<String>
    fun readText(path: String): String?
}

/**
 * 当前时间戳（毫秒）—— 写 history.executed_at 用。
 */
expect fun currentTimeMillis(): Long
