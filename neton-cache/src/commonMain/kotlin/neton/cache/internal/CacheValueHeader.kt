package neton.cache.internal

import neton.cache.CacheCodecKind

/**
 * v1 冻结：L2 value 格式 [MAGIC 1B][CODEC 1B][payload...]
 * MAGIC = 0x4E ('N')；CODEC: 0x00=null(无 payload)，0x01=ProtoBuf，0x02=Json。
 */
internal object CacheValueHeader {
    const val MAGIC: Byte = 0x4E
    const val CODEC_NULL: Byte = 0x00
    const val CODEC_PROTOBUF: Byte = 0x01
    const val CODEC_JSON: Byte = 0x02

    fun toCodecByte(kind: CacheCodecKind): Byte = when (kind) {
        CacheCodecKind.PROTOBUF -> CODEC_PROTOBUF
        CacheCodecKind.JSON -> CODEC_JSON
    }

    fun wrapNull(): ByteArray = byteArrayOf(MAGIC, CODEC_NULL)

    fun wrapValue(payload: ByteArray, kind: CacheCodecKind): ByteArray =
        byteArrayOf(MAGIC, toCodecByte(kind)) + payload

    fun isNull(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == MAGIC && bytes[1] == CODEC_NULL

    /** 返回 (payload, codecKind) 或 null（格式非法/null） */
    fun unwrap(bytes: ByteArray): Pair<ByteArray, CacheCodecKind>? {
        if (bytes.size < 2 || bytes[0] != MAGIC) return null
        val codec = bytes[1]
        if (codec == CODEC_NULL) return null
        val payload = bytes.copyOfRange(2, bytes.size)
        val kind = when (codec) {
            CODEC_PROTOBUF -> CacheCodecKind.PROTOBUF
            CODEC_JSON -> CacheCodecKind.JSON
            else -> return null
        }
        return payload to kind
    }
}
