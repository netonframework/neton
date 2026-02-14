package neton.cache.internal

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import neton.cache.CacheCodecKind

/**
 * v1 冻结：内部统一序列化；默认 ProtoBuf，可选 JSON（仅调试）。
 * value 存 Redis 时由 CacheValueHeader 加 [MAGIC][CODEC]+payload，此处只做 payload 编解码。
 */
@OptIn(ExperimentalSerializationApi::class)
internal object CacheCodec {
    private val protoBuf = ProtoBuf {}
    private val json = Json { ignoreUnknownKeys = true }

    fun <T : Any> encode(serializer: KSerializer<T>, value: T, kind: CacheCodecKind): ByteArray =
        when (kind) {
            CacheCodecKind.PROTOBUF -> protoBuf.encodeToByteArray(serializer, value)
            CacheCodecKind.JSON -> json.encodeToString(serializer, value).encodeToByteArray()
        }

    fun <T : Any> decode(serializer: KSerializer<T>, payload: ByteArray, kind: CacheCodecKind): T =
        when (kind) {
            CacheCodecKind.PROTOBUF -> protoBuf.decodeFromByteArray(serializer, payload)
            CacheCodecKind.JSON -> json.decodeFromString(serializer, payload.decodeToString())
        }
}
