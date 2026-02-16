package neton.core.http

/**
 * 上传文件抽象 - 平台无关接口
 */
interface UploadFile {
    /** 表单字段名 */
    val fieldName: String

    /** 原始文件名 */
    val filename: String

    /** MIME 类型 */
    val contentType: String?

    /** 文件大小（字节） */
    val size: Long

    /** 读取文件内容 */
    suspend fun bytes(): ByteArray
}

/**
 * 上传文件集合的结构化视图
 *
 * 底层保持 List（协议真实形态），提供按 fieldName 查找的便捷方法。
 */
class UploadFiles(private val parts: List<UploadFile>) {

    /** 所有上传文件 */
    fun all(): List<UploadFile> = parts

    /** 按 fieldName 获取文件列表 */
    fun get(name: String): List<UploadFile> =
        parts.filter { it.fieldName == name }

    /** 按 fieldName 获取第一个文件 */
    fun first(name: String): UploadFile? =
        parts.firstOrNull { it.fieldName == name }

    /** 按 fieldName 获取第一个文件，不存在则抛异常 */
    fun require(name: String): UploadFile =
        first(name) ?: throw BadRequestException("Missing upload file: $name")

    /** 转为 Map 视图 */
    fun asMap(): Map<String, List<UploadFile>> =
        parts.groupBy { it.fieldName }

    /** 是否为空 */
    fun isEmpty(): Boolean = parts.isEmpty()

    /** 文件总数 */
    val size: Int get() = parts.size
}
