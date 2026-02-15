package neton.storage.local

import neton.logging.Logger
import neton.storage.*
import neton.storage.internal.PathUtils
import neton.storage.internal.guessMimeType
import kotlin.time.Duration

internal class LocalStorageOperator(
    override val name: String,
    private val basePath: String,
    private val logger: Logger?
) : StorageOperator {

    override val scheme: String = "local"

    override suspend fun write(path: String, data: ByteArray, options: WriteOptions) {
        PathUtils.validatePath(path)
        val absPath = PathUtils.resolvePath(basePath, path)
        if (!options.overwrite && NativeFileSystem.fileExists(absPath)) {
            throw StorageAlreadyExistsException(path)
        }
        NativeFileSystem.writeFile(absPath, data)
    }

    override suspend fun read(path: String): ByteArray {
        PathUtils.validatePath(path)
        val absPath = PathUtils.resolvePath(basePath, path)
        if (!NativeFileSystem.fileExists(absPath)) {
            throw StorageNotFoundException(path)
        }
        return NativeFileSystem.readFile(absPath)
    }

    override suspend fun delete(path: String) {
        PathUtils.validatePath(path)
        val absPath = PathUtils.resolvePath(basePath, path)
        NativeFileSystem.deleteFile(absPath)
    }

    override suspend fun exists(path: String): Boolean {
        PathUtils.validatePath(path)
        val absPath = PathUtils.resolvePath(basePath, path)
        return NativeFileSystem.fileExists(absPath)
    }

    override suspend fun stat(path: String): FileStat {
        PathUtils.validatePath(path)
        val absPath = PathUtils.resolvePath(basePath, path)
        val nativeStat = NativeFileSystem.fileStat(absPath)
            ?: throw StorageNotFoundException(path)
        return FileStat(
            path = path,
            size = nativeStat.size,
            lastModified = nativeStat.lastModifiedMs,
            isDirectory = nativeStat.isDirectory,
            contentType = guessMimeType(path)
        )
    }

    override suspend fun list(path: String, options: ListOptions): List<FileEntry> {
        PathUtils.validatePath(path)
        val absPath = PathUtils.resolvePath(basePath, path)
        return if (options.recursive) {
            listRecursive(absPath, path, options.maxResults)
        } else {
            listFlat(absPath, path, options.maxResults)
        }
    }

    private fun listFlat(absPath: String, relPath: String, maxResults: Int): List<FileEntry> {
        val entries = NativeFileSystem.listDir(absPath)
        val prefix = if (relPath.isEmpty() || relPath.endsWith("/")) relPath else "$relPath/"
        return entries.take(maxResults).map { entry ->
            val entryPath = if (entry.isDirectory) "$prefix${entry.name}/" else "$prefix${entry.name}"
            if (entry.isDirectory) {
                FileEntry(path = entryPath, size = 0, lastModified = 0, isDirectory = true)
            } else {
                val childAbs = "$absPath/${entry.name}"
                val st = NativeFileSystem.fileStat(childAbs)
                FileEntry(
                    path = entryPath,
                    size = st?.size ?: 0,
                    lastModified = st?.lastModifiedMs ?: 0,
                    isDirectory = false
                )
            }
        }
    }

    private fun listRecursive(absPath: String, relPath: String, maxResults: Int): List<FileEntry> {
        val result = mutableListOf<FileEntry>()
        collectRecursive(absPath, relPath, result, maxResults)
        return result
    }

    private fun collectRecursive(absPath: String, relPath: String, result: MutableList<FileEntry>, maxResults: Int) {
        if (result.size >= maxResults) return
        val entries = NativeFileSystem.listDir(absPath)
        val prefix = if (relPath.isEmpty() || relPath.endsWith("/")) relPath else "$relPath/"
        for (entry in entries) {
            if (result.size >= maxResults) return
            val childAbs = "$absPath/${entry.name}"
            val childRel = "$prefix${entry.name}"
            if (entry.isDirectory) {
                collectRecursive(childAbs, childRel, result, maxResults)
            } else {
                val st = NativeFileSystem.fileStat(childAbs)
                result.add(
                    FileEntry(
                        path = childRel,
                        size = st?.size ?: 0,
                        lastModified = st?.lastModifiedMs ?: 0,
                        isDirectory = false
                    )
                )
            }
        }
    }

    override suspend fun copy(src: String, dst: String) {
        val data = read(src)
        write(dst, data)
    }

    override suspend fun move(src: String, dst: String) {
        PathUtils.validatePath(src)
        PathUtils.validatePath(dst)
        val absSrc = PathUtils.resolvePath(basePath, src)
        val absDst = PathUtils.resolvePath(basePath, dst)
        if (!NativeFileSystem.renameFile(absSrc, absDst)) {
            copy(src, dst)
            delete(src)
        }
    }

    override suspend fun presignRead(path: String, ttl: Duration): String {
        throw UnsupportedOperationException("Local storage does not support presigned URLs")
    }

    override suspend fun presignWrite(path: String, ttl: Duration): String {
        throw UnsupportedOperationException("Local storage does not support presigned URLs")
    }
}
