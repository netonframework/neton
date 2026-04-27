package neton.migrate.script

/**
 * SHA-256 of raw bytes — no normalization.
 * 决策：脚本内容一字节变化即视为变更。
 */
expect object Checksum {
    fun sha256Hex(bytes: ByteArray): String
}
