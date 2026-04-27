package neton.migrate.script

import neton.migrate.io.FileIO

object ScriptScanner {

    private val FILENAME_REGEX = Regex("""^V(\d+)__([a-z0-9_]+)\.sql$""")

    /**
     * 扫描目录下所有 V*.sql 文件，按 version 升序返回。
     * 不符合命名规范的文件会被跳过（不报错，但记录到 warnings）。
     */
    fun scan(dir: String): ScanResult {
        if (!FileIO.exists(dir)) {
            return ScanResult.DirNotFound(dir)
        }
        if (!FileIO.isDirectory(dir)) {
            return ScanResult.NotADirectory(dir)
        }

        val files = FileIO.listDir(dir).filter { it.endsWith(".sql") }
        val scripts = mutableListOf<ScriptFile>()
        val warnings = mutableListOf<String>()

        for (fileName in files) {
            val match = FILENAME_REGEX.matchEntire(fileName)
            if (match == null) {
                warnings += "skipped (bad name): $fileName"
                continue
            }
            val version = match.groupValues[1]
            val description = match.groupValues[2]
            val path = "$dir/$fileName"
            val content = FileIO.readText(path)
                ?: run {
                    warnings += "skipped (unreadable): $fileName"
                    continue
                }
            val checksum = Checksum.sha256Hex(content.encodeToByteArray())
            scripts += ScriptFile(
                version = version,
                description = description,
                fileName = fileName,
                absolutePath = path,
                content = content,
                checksum = checksum
            )
        }

        // 按 version 升序（数字比较，padStart 防止 "10" < "2" 的字典序坑）
        val maxLen = scripts.maxOfOrNull { it.version.length } ?: 0
        val sorted = scripts.sortedBy { it.version.padStart(maxLen, '0') }

        // 检查 version 唯一性
        val dupes = sorted.groupBy { it.version }.filter { it.value.size > 1 }.keys
        if (dupes.isNotEmpty()) {
            return ScanResult.DuplicateVersion(dupes.toList(), sorted, warnings)
        }

        return ScanResult.Ok(sorted, warnings)
    }

    sealed class ScanResult {
        data class Ok(val scripts: List<ScriptFile>, val warnings: List<String>) : ScanResult()
        data class DirNotFound(val dir: String) : ScanResult()
        data class NotADirectory(val dir: String) : ScanResult()
        data class DuplicateVersion(val versions: List<String>, val scripts: List<ScriptFile>, val warnings: List<String>) : ScanResult()
    }
}
