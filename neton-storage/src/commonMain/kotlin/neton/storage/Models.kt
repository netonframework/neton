package neton.storage

import kotlin.time.Duration

/**
 * 文件元信息
 */
data class FileStat(
    val path: String,
    val size: Long,
    val lastModified: Long,
    val isDirectory: Boolean,
    val contentType: String? = null
)

/**
 * 列表条目
 */
data class FileEntry(
    val path: String,
    val size: Long,
    val lastModified: Long,
    val isDirectory: Boolean
)

/**
 * 写入选项
 */
data class WriteOptions(
    val contentType: String? = null,
    val overwrite: Boolean = true
)

/**
 * 列表选项
 */
data class ListOptions(
    val recursive: Boolean = false,
    val maxResults: Int = 1000
)
