package controller

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.serialization.Serializable
import neton.core.annotations.AllowAnonymous
import neton.core.annotations.Body
import neton.core.annotations.Controller
import neton.core.annotations.Put
import neton.validation.annotations.NotBlank
import neton.validation.annotations.Size

/**
 * 声明式校验的执行点：请求体在 handler 运行**之前**由生成的校验器拦下。
 * `nickname` 用主构造参数上的裸注解写法，`password` 用 `@property:` 写法，两种都必须生效。
 */
@Serializable
data class ChangePasswordRequest(
    @property:NotBlank
    @property:Size(min = 8, max = 64)
    val password: String,
    @Size(min = 2, max = 16)
    val nickname: String = "neton",
)

@OptIn(ExperimentalAtomicApi::class)
@Controller("/validation")
class ValidationDemoController {

    @Put("/password")
    @AllowAnonymous
    suspend fun changePassword(@Body request: ChangePasswordRequest): String {
        invocations.addAndFetch(1)
        return "changed"
    }

    companion object {
        /** handler 实际执行次数：校验失败的请求不许走到这里 */
        val invocations = AtomicInt(0)
    }
}
