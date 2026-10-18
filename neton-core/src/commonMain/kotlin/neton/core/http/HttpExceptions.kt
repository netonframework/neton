package neton.core.http

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * 控制器抛出此异常时，HTTP 适配器将响应 404 Not Found。
 */
class NotFoundException(message: String? = null) :
    HttpException(NetonErrorCode.RESOURCE_NOT_FOUND, message ?: NetonErrorMessage.RESOURCE_NOT_FOUND)

/**
 * Content-Type 不支持时抛出，适配器响应 415 Unsupported Media Type
 */
class UnsupportedMediaTypeException(message: String = NetonErrorMessage.UNSUPPORTED_MEDIA_TYPE) :
    HttpException(NetonErrorCode.INVALID_PARAM_TYPE, message)

/**
 * 分布式锁未获取时抛出（如 neton-redis LockManager.withLock 拿不到锁）。
 * v1 固定映射 HTTP 409 Conflict。
 */
class LockNotAcquiredException(
    message: String = NetonErrorMessage.LOCK_NOT_ACQUIRED,
    val key: String
) : HttpException(NetonErrorCode.OPERATION_CONFLICT, message)

/**
 * 参数校验失败，适配器响应 400；信封 `message` 为 [NetonErrorMessage.VALIDATION_FAILED]，细节在 `errors[]`。
 */
class ValidationException(
    errors: List<ValidationError>,
    message: String = NetonErrorMessage.VALIDATION_FAILED
) : BadRequestException(message, errors)

/**
 * 字段级错误（HTTP/非 HTTP 通用，可序列化）；对外统一用 path。
 *
 * - [code]：机器码（框架内建见 [ValidationCode]，业务码见各模块 `<Module>ErrorCodes`）；客户端按它翻译
 * - [message]：与 [code] 相同的机器码——不放句子
 * - [params]：客户端渲染需要的约束值（如 `min`/`max`），没有时为 null，不输出
 *
 * 新代码用 [ValidationError.of]，保证 message 与 code 一致。
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ValidationError(
    val path: String,
    val message: String,
    val code: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val params: Map<String, Long>? = null,
) {
    companion object {
        /** 字段错误：`message` 与 `code` 都是 [code]。 */
        fun of(path: String, code: String, params: Map<String, Long>? = null): ValidationError =
            ValidationError(path = path, message = code, code = code, params = params?.takeIf { it.isNotEmpty() })
    }
}
