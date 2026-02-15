package neton.storage.internal

internal object PathUtils {

    fun validatePath(path: String) {
        if (path.contains("..")) {
            throw IllegalArgumentException("Path traversal not allowed: $path")
        }
        if (path.startsWith("/") || path.startsWith("\\")) {
            throw IllegalArgumentException("Absolute path not allowed: $path")
        }
        // Block Windows drive letters like C:\, D:/
        if (path.length >= 2 && path[1] == ':' && path[0].isLetter()) {
            throw IllegalArgumentException("Absolute path not allowed: $path")
        }
    }

    fun resolvePath(basePath: String, path: String): String {
        val base = basePath.trimEnd('/')
        val normalized = normalizePath(path)
        return "$base/$normalized"
    }

    fun parentDir(path: String): String? {
        val slash = path.lastIndexOf('/')
        return if (slash > 0) path.substring(0, slash) else null
    }

    /** Normalize: replace backslashes with /, collapse consecutive // */
    private fun normalizePath(path: String): String {
        var result = path.replace('\\', '/')
        while (result.contains("//")) {
            result = result.replace("//", "/")
        }
        return result
    }
}
