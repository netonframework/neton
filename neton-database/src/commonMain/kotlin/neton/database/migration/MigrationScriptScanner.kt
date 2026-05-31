package neton.database.migration

/**
 * 扫描 [MigrationSource.resourcePath] 下所有 V*.sql 文件,按 version 升序返回。
 * 不符合命名规范的文件被跳过(不报错,但记入 warnings)。
 */
internal object MigrationScriptScanner {

    private val FILENAME_REGEX = Regex("""^V(\d+)__([a-z0-9_]+)\.sql$""")

    fun scan(source: MigrationSource): ScanResult {
        val dir = source.resourcePath
        if (!MigrationFileIO.exists(dir)) {
            return ScanResult.DirNotFound(source, dir)
        }
        if (!MigrationFileIO.isDirectory(dir)) {
            return ScanResult.NotADirectory(source, dir)
        }

        val files = MigrationFileIO.listDir(dir).filter { it.endsWith(".sql") }
        val scripts = mutableListOf<MigrationScript>()
        val warnings = mutableListOf<String>()

        for (fileName in files) {
            val match = FILENAME_REGEX.matchEntire(fileName)
            if (match == null) {
                warnings += "[${source.moduleId}] skipped (bad name): $fileName"
                continue
            }
            val version = match.groupValues[1]
            val description = match.groupValues[2]
            val path = "$dir/$fileName"
            val content = MigrationFileIO.readText(path)
                ?: run {
                    warnings += "[${source.moduleId}] skipped (unreadable): $fileName"
                    continue
                }
            val checksum = Checksum.sha256Hex(content.encodeToByteArray())
            scripts += MigrationScript(
                moduleId = source.moduleId,
                version = version,
                description = description,
                fileName = fileName,
                absolutePath = path,
                content = content,
                checksum = checksum,
            )
        }

        val maxLen = scripts.maxOfOrNull { it.version.length } ?: 0
        val sorted = scripts.sortedBy { it.version.padStart(maxLen, '0') }

        val dupes = sorted.groupBy { it.version }.filter { it.value.size > 1 }.keys
        if (dupes.isNotEmpty()) {
            return ScanResult.DuplicateVersion(source, dupes.toList(), sorted, warnings)
        }

        return ScanResult.Ok(source, sorted, warnings)
    }

    sealed class ScanResult {
        abstract val source: MigrationSource
        data class Ok(
            override val source: MigrationSource,
            val scripts: List<MigrationScript>,
            val warnings: List<String>,
        ) : ScanResult()
        data class DirNotFound(override val source: MigrationSource, val dir: String) : ScanResult()
        data class NotADirectory(override val source: MigrationSource, val dir: String) : ScanResult()
        data class DuplicateVersion(
            override val source: MigrationSource,
            val versions: List<String>,
            val scripts: List<MigrationScript>,
            val warnings: List<String>,
        ) : ScanResult()
    }
}
