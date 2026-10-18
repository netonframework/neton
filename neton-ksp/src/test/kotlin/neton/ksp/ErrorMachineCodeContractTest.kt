package neton.ksp

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.kspSourcesDir
import neton.ksp.validation.ValidationProcessorProvider
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * 「消息即机器码」在 KSP 生成层的契约（spec ERROR_CODE_SPEC §机器码）：
 *
 * - 声明式校验：字段 `code` = 作者给的机器码，或约束的内建码；`message` 同 `code`；约束值进 `params`
 * - 作者在校验注解 / `@RateLimit` 上写句子 → 编译失败
 * - 参数绑定失败的字段码：`REQUIRED` / `INVALID_INTEGER` / …；请求体解析失败 → `INVALID_JSON`
 */
@OptIn(ExperimentalCompilerApi::class)
class ErrorMachineCodeContractTest {

    // ---- 声明式校验 ----

    @Test
    fun builtInCodesAreUsedWhenNoMessageIsGiven() {
        val src = compileValidation(
            """
            data class Form(
                @NotBlank val name: String,
                @NotNull val nick: String?,
                @Size(min = 2, max = 32) val title: String,
                @Size(min = 1) val tags: List<String>,
                @Min(1) val age: Int,
                @Max(120) val score: Long,
                @Pattern(regex = "^[0-9]+$") val code: String,
                @Email val email: String,
            )
            """,
        ).generatedSource
        assertContains(src, """ValidationError.of("name", "REQUIRED")""")
        assertContains(src, """ValidationError.of("nick", "REQUIRED")""")
        assertContains(src, """ValidationError.of("title", "TOO_SHORT", mapOf("min" to 2L, "max" to 32L))""")
        assertContains(src, """ValidationError.of("title", "TOO_LONG", mapOf("min" to 2L, "max" to 32L))""")
        // max 缺省（Long.MAX_VALUE）时不生成上限检查，也不下发 max
        assertContains(src, """ValidationError.of("tags", "TOO_FEW_ITEMS", mapOf("min" to 1L))""")
        assertFalse(src.contains("TOO_MANY_ITEMS"), src)
        assertContains(src, """ValidationError.of("age", "TOO_SMALL", mapOf("min" to 1L))""")
        assertContains(src, """ValidationError.of("score", "TOO_LARGE", mapOf("max" to 120L))""")
        assertContains(src, """ValidationError.of("code", "INVALID_FORMAT")""")
        assertContains(src, """ValidationError.of("email", "INVALID_EMAIL")""")
        // 不再有句子或约束名做 code
        listOf("must not be blank", "must be", "size must", "invalid email", "\"NotBlank\"", "\"Size\"").forEach {
            assertFalse(src.contains(it), "生成代码里不该出现 $it：\n$src")
        }
    }

    @Test
    fun authorCodeReplacesTheBuiltInCode() {
        val src = compileValidation(
            """
            data class Form(
                @Pattern(regex = "^1[0-9]{10}$", message = "INVALID_MOBILE") val mobile: String,
                @Size(min = 8, max = 64, message = "PASSWORD_LENGTH") val password: String,
            )
            """,
        ).generatedSource
        assertContains(src, """ValidationError.of("mobile", "INVALID_MOBILE")""")
        // 作者给了码：两侧都用这一个码，约束值仍在 params
        assertContains(src, """ValidationError.of("password", "PASSWORD_LENGTH", mapOf("min" to 8L, "max" to 64L))""")
        assertFalse(src.contains("TOO_SHORT"), src)
    }

    @Test
    fun sentenceMessageOnAConstraintFailsTheBuild() {
        val compiled = compileValidation(
            """
            data class Form(@NotBlank(message = "name must not be blank") val name: String)
            """,
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, compiled.result.exitCode, compiled.result.messages)
        assertContains(compiled.result.messages, "is not a machine code")
    }

    // ---- @RateLimit ----

    @Test
    fun rateLimitDefaultsToRateLimited() {
        val src = compileController(
            """
            @Post("/a")
            @RateLimit(windowSeconds = 60, maxRequests = 5)
            suspend fun a(): String = "a"
            """,
        ).generatedSource
        assertContains(src, """message = "RATE_LIMITED"""")
    }

    @Test
    fun rateLimitKeepsTheModuleCode() {
        val src = compileController(
            """
            @Post("/a")
            @RateLimit(windowSeconds = 60, maxRequests = 5, message = "LOGIN_RATE_LIMITED")
            suspend fun a(): String = "a"
            """,
        ).generatedSource
        assertContains(src, """message = "LOGIN_RATE_LIMITED"""")
    }

    @Test
    fun rateLimitSentenceFailsTheBuild() {
        val compiled = compileController(
            """
            @Post("/a")
            @RateLimit(windowSeconds = 60, maxRequests = 5, message = "Too many requests")
            suspend fun a(): String = "a"
            """,
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, compiled.result.exitCode, compiled.result.messages)
        assertContains(compiled.result.messages, "Neton @RateLimit")
        assertContains(compiled.result.messages, "is not a machine code")
    }

    // ---- 参数绑定 ----

    @Test
    fun bindingFailuresUseFieldMachineCodes() {
        val src = compileController(
            """
            @Get("/a")
            suspend fun a(@QueryParam("n") n: Int, @QueryParam("f") f: Boolean, @QueryParam("d") d: Double): String = "a"

            @Post("/b")
            suspend fun b(payload: Payload): String = "b"
            """,
        ).generatedSource
        assertContains(src, """ValidationError.of("n", "REQUIRED")""")
        assertContains(src, """ValidationError.of("n", "INVALID_INTEGER")""")
        assertContains(src, """ValidationError.of("f", "INVALID_BOOLEAN")""")
        assertContains(src, """ValidationError.of("d", "INVALID_NUMBER")""")
        assertContains(src, """HttpException(neton.core.http.NetonErrorCode.INVALID_JSON, "INVALID_JSON", listOf(ValidationError.of("\${'$'}", "INVALID_JSON")))""")
        listOf("is required", "must be a valid", "Invalid JSON body", "\"Missing\"", "\"Type\"", "\"InvalidJson\"").forEach {
            assertFalse(src.contains(it), "生成代码里不该出现 $it")
        }
    }

    // ---- fixtures ----

    private class Compiled(val result: JvmCompilationResult, val generatedSource: String)

    private val validationAnnotations = SourceFile.kotlin(
        "ValidationStubs.kt",
        """
        package neton.validation.annotations

        @Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY) annotation class NotBlank(val message: String = "")
        @Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY) annotation class NotNull(val message: String = "")
        @Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY) annotation class Min(val value: Long, val message: String = "")
        @Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY) annotation class Max(val value: Long, val message: String = "")
        @Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY) annotation class Size(val min: Long = 0, val max: Long = Long.MAX_VALUE, val message: String = "")
        @Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY) annotation class Pattern(val regex: String, val message: String = "")
        @Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY) annotation class Email(val message: String = "")
        """.trimIndent(),
    )

    private val httpAnnotations = SourceFile.kotlin(
        "HttpAnnotationStubs.kt",
        """
        package neton.core.annotations

        annotation class Controller(val value: String = "")
        annotation class Get(val value: String = "")
        annotation class Post(val value: String = "")
        annotation class QueryParam(val value: String = "")
        enum class RateLimitScope { USER, IP, GLOBAL, CUSTOM }
        enum class RateLimitStrategy { FIXED_WINDOW, SLIDING_WINDOW, TOKEN_BUCKET }
        annotation class RateLimit(
            val windowSeconds: Int,
            val maxRequests: Int,
            val scope: RateLimitScope = RateLimitScope.USER,
            val key: String = "",
            val strategy: RateLimitStrategy = RateLimitStrategy.FIXED_WINDOW,
            val message: String = "RATE_LIMITED",
        )
        """.trimIndent(),
    )

    private fun compileValidation(body: String): Compiled = run(
        SourceFile.kotlin(
            "Form.kt",
            "package fixture\n\nimport neton.validation.annotations.*\n\n" + body.trimIndent(),
        ),
        validationAnnotations,
        provider = ValidationProcessorProvider(),
    )

    private fun compileController(body: String): Compiled = run(
        SourceFile.kotlin(
            "Fixture.kt",
            """
            package fixture

            import neton.core.annotations.*

            data class Payload(val q: String)

            @Controller("/fixture")
            class FixtureController {
            ${body.trimIndent()}
            }
            """.trimIndent(),
        ),
        httpAnnotations,
        provider = ControllerProcessorProvider(),
    )

    private fun run(
        vararg sources: SourceFile,
        provider: com.google.devtools.ksp.processing.SymbolProcessorProvider,
    ): Compiled {
        val compilation = KotlinCompilation().apply {
            this.sources = sources.toList()
            inheritClassPath = true
            messageOutputStream = java.io.OutputStream.nullOutputStream()
            configureKsp {
                symbolProcessorProviders += provider
                // 生成代码引用 Native-only 运行时类型，只跑处理器
                withCompilation = false
            }
        }
        val result = compilation.compile()
        val generated = compilation.kspSourcesDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }
        return Compiled(result, generated)
    }
}
