package neton.storage.local

internal data class NativeFileStat(
    val size: Long,
    val lastModifiedMs: Long,
    val isDirectory: Boolean
)

internal data class NativeDirEntry(
    val name: String,
    val isDirectory: Boolean
)

internal expect object NativeFileSystem {
    fun readFile(absolutePath: String): ByteArray
    fun writeFile(absolutePath: String, data: ByteArray)
    fun deleteFile(absolutePath: String)
    fun fileExists(absolutePath: String): Boolean
    fun fileStat(absolutePath: String): NativeFileStat?
    fun listDir(absolutePath: String): List<NativeDirEntry>
    fun mkdirs(absolutePath: String)
    fun renameFile(src: String, dst: String): Boolean
}
