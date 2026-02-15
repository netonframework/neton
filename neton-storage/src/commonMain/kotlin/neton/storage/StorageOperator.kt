package neton.storage

import kotlin.time.Duration

/**
 * 统一存储操作接口（借鉴 OpenDAL Operator）。
 *
 * path 统一为相对路径，不含前导 `/`。
 * Phase 1 仅适用于 ≤32MB 小文件。
 */
interface StorageOperator {
    /** 后端类型标识："local" | "s3" */
    val scheme: String

    /** 当前源的配置名称（对应 [[sources]] 的 name） */
    val name: String

    /** 写入文件 */
    suspend fun write(path: String, data: ByteArray, options: WriteOptions = WriteOptions())

    /** 读取文件内容 */
    suspend fun read(path: String): ByteArray

    /** 删除文件 */
    suspend fun delete(path: String)

    /** 判断文件是否存在 */
    suspend fun exists(path: String): Boolean

    /** 获取文件元信息 */
    suspend fun stat(path: String): FileStat

    /** 列出目录/前缀下的文件 */
    suspend fun list(path: String, options: ListOptions = ListOptions()): List<FileEntry>

    /** 复制文件 */
    suspend fun copy(src: String, dst: String)

    /** 移动/重命名文件 */
    suspend fun move(src: String, dst: String)

    /** 生成预签名读取 URL（S3 专属，Local 抛 UnsupportedOperationException） */
    suspend fun presignRead(path: String, ttl: Duration): String

    /** 生成预签名上传 URL（S3 专属，Local 抛 UnsupportedOperationException） */
    suspend fun presignWrite(path: String, ttl: Duration): String
}
