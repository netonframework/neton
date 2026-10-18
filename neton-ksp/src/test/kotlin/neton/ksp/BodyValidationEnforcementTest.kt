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
import kotlin.test.assertTrue

/**
 * 请求体声明式校验**必须执行**（安全回归）。
 *
 * 旧实现：每个模块把自己的校验器注册表 `bindIfAbsent(ValidatorRegistry)` 进上下文，生成的路由在
 * 运行期按类型查，查不到就跳过。多模块宿主里只有第一个装配的模块（system）的注册表生效，
 * account / member / payment / … 的 DTO 全部静默放行；单应用模式根本没人绑定注册表。
 *
 * 新契约：每个约束 DTO 生成同包的 `<Name>NetonValidator`，路由代码**按名直接调用**；
 * 缺校验器 = 编译失败（未解析引用），约束写在不会执行的位置 = 编译失败。
 */
@OptIn(ExperimentalCompilerApi::class)
class BodyValidationEnforcementTest {

    // ---- 生成：每个约束 DTO 一个同包校验器 ----

    @Test
    fun eachConstrainedDtoGetsAValidatorInItsOwnPackage() {
        val compiled = compile(
            """
            data class PwRequest(@property:NotBlank @property:Size(min = 8) val newPassword: String)
            internal data class Hidden(@Size(min = 1) val v: String)
            class Outer { data class Inner(@NotBlank val x: String) }
            data class Plain(val q: String)
            """,
            ValidationProcessorProvider(),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, compiled.result.exitCode, compiled.result.messages)
        val src = compiled.generatedSource
        assertContains(src, "public object PwRequestNetonValidator : neton.validation.Validator<fixture.PwRequest>")
        assertContains(src, "internal object HiddenNetonValidator : neton.validation.Validator<fixture.Hidden>")
        assertContains(src, "public object Outer_InnerNetonValidator : neton.validation.Validator<fixture.Outer.Inner>")
        assertFalse(src.contains("PlainNetonValidator"), src)
        assertFalse(src.contains("ValidatorRegistry"), "不再有运行期注册表：\n$src")
    }

    // ---- 路由：直接调用，不查注册表 ----

    @Test
    fun routeCallsTheGeneratedValidatorDirectly() {
        val compiled = compileController(
            """
            @Put("/pw") suspend fun pw(@Body request: PwRequest): String = "ok"
            @Post("/plain") suspend fun plain(@Body request: Plain): String = "ok"
            @Post("/batch") suspend fun batch(@Body request: List<PwRequest>): String = "ok"
            """,
        )
        // 路由生成代码引用 Native-only 类型，JVM 上编不过；这里只要求处理器本身没报错
        assertFalse(compiled.result.messages.lineSequence().any { it.startsWith("e: [ksp]") }, compiled.result.messages)
        val src = compiled.generatedSource
        assertContains(src, "fixture.PwRequestNetonValidator.validate(body).takeIf { it.isNotEmpty() }?.let { throw ValidationException(it) }")
        // List<Dto>：逐元素校验，路径带下标
        assertContains(src, "body.forEachIndexed { _i, _item -> _item?.let { _x -> fixture.PwRequestNetonValidator.validate(_x)")
        // 没有约束的 DTO 不生成校验调用；任何路径都不查注册表
        assertFalse(src.contains("PlainNetonValidator"), src)
        assertFalse(src.contains("ValidatorRegistry"), src)
        assertFalse(src.contains("validator?.validate"), src)
        // 校验在 handler 调用参数求值内、handler 执行之前
        val call = src.indexOf("ctrl.pw(")
        val check = src.indexOf("fixture.PwRequestNetonValidator.validate(body)")
        assertTrue(call in 0 until check, "校验必须在 handler 参数求值里完成：\n$src")
    }

    // ---- 写了不生效的约束 → 编译失败（fail closed） ----

    @Test
    fun constraintOnAHandlerParameterFailsTheBuild() = assertRejected(
        compileController("""@Get("/q") suspend fun q(@Size(min = 2) @QueryParam("k") k: String): String = k"""),
        "is never enforced",
    )

    @Test
    fun constraintOnANonPropertyConstructorParameterFailsTheBuild() = assertRejected(
        compile("class Form(@NotBlank name: String) { val n = name }", ValidationProcessorProvider()),
        "is never enforced",
    )

    @Test
    fun sizeOnAnUnsupportedTypeFailsTheBuild() = assertRejected(
        compile("data class Form(@Size(min = 1) val n: Int)", ValidationProcessorProvider()),
        "@Size supports",
    )

    @Test
    fun validOnATypeWithoutConstraintsFailsTheBuild() = assertRejected(
        compile(
            """
            data class Child(val x: String)
            data class Form(@Valid val child: Child)
            """,
            ValidationProcessorProvider(),
        ),
        "@Valid requires",
    )

    @Test
    fun privateDtoFailsTheBuild() = assertRejected(
        compile("private data class Form(@NotBlank val n: String)", ValidationProcessorProvider()),
        "private/local",
    )

    // ---- 生成的校验器真的编译、真的拦得住（JVM 上用运行时桩跑一遍） ----

    @Test
    fun generatedValidatorRejectsAndAccepts() {
        val compilation = KotlinCompilation().apply {
            sources = listOf(
                validationAnnotations, runtimeStubs, validatorStub,
                SourceFile.kotlin(
                    "Form.kt",
                    """
                    package fixture
                    import neton.validation.annotations.*
                    data class Child(@NotBlank val code: String)
                    data class PwRequest(
                        @property:NotBlank @property:Size(min = 8, max = 64) val newPassword: String,
                        @Valid val child: Child? = null,
                        @Valid val items: List<Child> = emptyList(),
                    )
                    """.trimIndent(),
                ),
            )
            inheritClassPath = true
            messageOutputStream = java.io.OutputStream.nullOutputStream()
            configureKsp { symbolProcessorProviders += ValidationProcessorProvider() }
        }
        val result = compilation.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val loader = result.classLoader
        val dtoClass = loader.loadClass("fixture.PwRequest")
        val childClass = loader.loadClass("fixture.Child")
        val validator = loader.loadClass("fixture.PwRequestNetonValidator")
        val instance = validator.getField("INSTANCE").get(null)
        val validate = validator.methods.first { it.name == "validate" && it.parameterTypes.single() == dtoClass }
        val ctor = dtoClass.constructors.first { it.parameterCount == 3 }
        val child = { code: String -> childClass.constructors.single().newInstance(code) }

        fun codes(dto: Any): List<String> = (validate.invoke(instance, dto) as List<*>).map { e ->
            val path = e!!.javaClass.getMethod("getPath").invoke(e)
            val code = e.javaClass.getMethod("getCode").invoke(e)
            "$path:$code"
        }

        assertEquals(listOf("newPassword:REQUIRED", "newPassword:TOO_SHORT"), codes(ctor.newInstance("", null, emptyList<Any>())))
        assertEquals(listOf("newPassword:TOO_SHORT"), codes(ctor.newInstance("abc", null, emptyList<Any>())))
        assertEquals(
            listOf("child.code:REQUIRED", "items[1].code:REQUIRED"),
            codes(ctor.newInstance("long-enough", child(" "), listOf(child("ok"), child("")))),
        )
        assertEquals(emptyList(), codes(ctor.newInstance("long-enough", child("ok"), listOf(child("ok")))))
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
        @Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY) annotation class Valid
        """.trimIndent(),
    )

    /** neton-validation / neton-core 的最小 JVM 桩：只够让生成的校验器编译运行 */
    private val runtimeStubs = SourceFile.kotlin(
        "RuntimeStubs.kt",
        """
        package neton.core.http
        data class ValidationError(val path: String, val message: String, val code: String? = null, val params: Map<String, Long>? = null) {
            companion object {
                fun of(path: String, code: String, params: Map<String, Long>? = null) = ValidationError(path, code, code, params)
            }
        }
        """.trimIndent(),
    )
    private val validatorStub = SourceFile.kotlin(
        "ValidatorStub.kt",
        """
        package neton.validation
        fun interface Validator<T : Any> { fun validate(value: T): List<neton.core.http.ValidationError> }
        """.trimIndent(),
    )

    private val httpAnnotations = SourceFile.kotlin(
        "HttpAnnotationStubs.kt",
        """
        package neton.core.annotations

        annotation class Controller(val value: String = "")
        annotation class Get(val value: String = "")
        annotation class Post(val value: String = "")
        annotation class Put(val value: String = "")
        annotation class Body
        annotation class QueryParam(val value: String = "")
        """.trimIndent(),
    )

    private fun compile(body: String, vararg providers: com.google.devtools.ksp.processing.SymbolProcessorProvider): Compiled =
        run(
            listOf(
                validationAnnotations,
                SourceFile.kotlin("Form.kt", "package fixture\n\nimport neton.validation.annotations.*\n\n" + body.trimIndent()),
            ),
            providers.toList(),
        )

    private fun compileController(body: String): Compiled = run(
        listOf(
            validationAnnotations, httpAnnotations,
            SourceFile.kotlin(
                "Fixture.kt",
                """
                package fixture

                import neton.core.annotations.*
                import neton.validation.annotations.*

                data class PwRequest(@property:NotBlank @property:Size(min = 8) val newPassword: String)
                data class Plain(val q: String)

                @Controller("/fixture")
                class FixtureController {
                ${body.trimIndent()}
                }
                """.trimIndent(),
            ),
        ),
        listOf(ControllerProcessorProvider(), ValidationProcessorProvider()),
    )

    private fun run(
        sources: List<SourceFile>,
        providers: List<com.google.devtools.ksp.processing.SymbolProcessorProvider>,
    ): Compiled {
        val compilation = KotlinCompilation().apply {
            this.sources = sources + validatorStub + runtimeStubs
            inheritClassPath = true
            messageOutputStream = java.io.OutputStream.nullOutputStream()
            configureKsp {
                symbolProcessorProviders += providers
                // 路由生成代码引用 Native-only 运行时类型，只跑处理器
                withCompilation = false
            }
        }
        val result = compilation.compile()
        val generated = compilation.kspSourcesDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }
        return Compiled(result, generated)
    }

    private fun assertRejected(compiled: Compiled, vararg fragments: String) {
        assertEquals(
            KotlinCompilation.ExitCode.COMPILATION_ERROR, compiled.result.exitCode,
            "写了不生效的约束必须让编译失败：\n${compiled.result.messages}",
        )
        fragments.forEach { assertContains(compiled.result.messages, it) }
    }
}
