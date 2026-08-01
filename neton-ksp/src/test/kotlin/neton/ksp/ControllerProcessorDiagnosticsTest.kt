package neton.ksp

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.kspSourcesDir
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 真正跑一遍 kotlinc + KSP，验证诊断**能阻断编译**。
 *
 * [neton.ksp.keys.KeySourceResolverTest] 测的是纯判定逻辑，覆盖不到「KSP 符号 → KeyParam 的翻译」
 * 和「报错是否真的让构建失败」这两层——参数别名那个 bug 恰恰就落在翻译层，纯函数测试看不见。
 *
 * 注解在这里用同名桩重新声明：处理器一律按**全限定名字符串**识别注解，不依赖运行时模块，
 * 所以 Native-only 的 neton-core / neton-cache 不需要（也不可能）出现在 JVM 测试的 classpath 上。
 */
@OptIn(ExperimentalCompilerApi::class)
class ControllerProcessorDiagnosticsTest {

    private val httpAnnotations = SourceFile.kotlin(
        "HttpAnnotationStubs.kt",
        """
        package neton.core.annotations

        annotation class Controller(val value: String = "")
        annotation class Get(val value: String = "")
        annotation class Post(val value: String = "")
        annotation class PathVariable(val value: String = "")
        annotation class QueryParam(val value: String = "")
        """.trimIndent(),
    )

    private val cacheAnnotations = SourceFile.kotlin(
        "CacheAnnotationStubs.kt",
        """
        package neton.cache

        annotation class Cacheable(val name: String, val key: String = "", val ttlMs: Long = 0L)
        annotation class CachePut(val name: String, val key: String = "", val ttlMs: Long = 0L)
        annotation class CacheEvict(val name: String, val key: String = "", val allEntries: Boolean = false)
        """.trimIndent(),
    )

    private val lockAnnotation = SourceFile.kotlin(
        "LockAnnotationStub.kt",
        """
        package neton.redis.lock

        annotation class Lock(val key: String = "lock", val ttlMs: Long = 30000L)
        """.trimIndent(),
    )

    private class Compiled(val result: JvmCompilationResult, val generatedSource: String)

    private fun compile(body: String): Compiled {
        val fixture = SourceFile.kotlin(
            "Fixture.kt",
            """
            package fixture

            import neton.core.annotations.Controller
            import neton.core.annotations.Get
            import neton.core.annotations.Post
            import neton.core.annotations.PathVariable
            import neton.core.annotations.QueryParam
            import neton.cache.Cacheable
            import neton.cache.CachePut
            import neton.cache.CacheEvict
            import neton.redis.lock.Lock

            data class Payload(val q: String)
            data class Reply(val v: String)

            @Controller("/fixture")
            class FixtureController {
            $body
            }
            """.trimIndent(),
        )
        val compilation = KotlinCompilation().apply {
            sources = listOf(httpAnnotations, cacheAnnotations, lockAnnotation, fixture)
            inheritClassPath = true
            messageOutputStream = java.io.OutputStream.nullOutputStream()
            configureKsp {
                symbolProcessorProviders += ControllerProcessorProvider()
                // 生成的源码引用 Native-only 的运行时类型，在 JVM 上编译不过；
                // 这里要验证的是 KSP 阶段的诊断，所以只跑处理器不跑后续编译
                withCompilation = false
            }
        }
        val result = compilation.compile()
        val generated = compilation.kspSourcesDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }
        return Compiled(result, generated)
    }

    private fun assertRejected(body: String, vararg expectedFragments: String) {
        val compiled = compile(body)
        assertEquals(
            KotlinCompilation.ExitCode.COMPILATION_ERROR,
            compiled.result.exitCode,
            "诊断必须让编译失败，否则等于没拦住：\n${compiled.result.messages}",
        )
        expectedFragments.forEach { assertContains(compiled.result.messages, it) }
    }

    private fun assertAccepted(body: String) {
        val messages = compile(body).result.messages
        assertFalse(
            messages.lineSequence().any { it.startsWith("e:") && it.contains("Neton @") },
            "不该报 Neton 诊断，实际输出：\n$messages",
        )
    }

    // ---- 绑定别名：args 里的键是别名，不是 Kotlin 参数名 ----

    @Test
    fun keyUsingParameterNameInsteadOfAliasIsRejected() = assertRejected(
        """
        @Get("/a/{id}")
        @Cacheable(name = "c", key = "{userId}")
        suspend fun a(@PathVariable("id") userId: Long): Reply = Reply("x")
        """,
        "{userId}",
        "use {id} instead of {userId}",
    )

    @Test
    fun keyUsingTheBindingAliasIsAccepted() = assertAccepted(
        """
        @Get("/a/{id}")
        @Cacheable(name = "c", key = "{id}")
        suspend fun a(@PathVariable("id") userId: Long): Reply = Reply("x")
        """,
    )

    @Test
    fun queryParamAliasIsHonoured() = assertAccepted(
        """
        @Get("/a")
        @Cacheable(name = "c", key = "{page}")
        suspend fun a(@QueryParam("page") pageNo: Int): Reply = Reply("x")
        """,
    )

    // ---- 取不到值的 key 来源 ----

    @Test
    fun bodyParameterInDefaultKeyIsRejected() = assertRejected(
        """
        @Post("/a")
        @Cacheable(name = "c")
        suspend fun a(payload: Payload): Reply = Reply(payload.q)
        """,
        "cannot take part in the default key",
        "payload",
    )

    @Test
    fun nestedPlaceholderIsRejected() = assertRejected(
        """
        @Get("/a/{id}")
        @Cacheable(name = "c", key = "{user.id}")
        suspend fun a(id: Long): Reply = Reply("x")
        """,
        "{user.id}",
        "Nested paths",
    )

    @Test
    fun pathParameterInDefaultKeyIsAccepted() = assertAccepted(
        """
        @Get("/a/{id}")
        @Cacheable(name = "c")
        suspend fun a(id: Long): Reply = Reply("x")
        """,
    )

    // ---- 织入点：只有路由方法才会生成 handler ----

    @Test
    fun cacheOnNonRouteMethodInsideControllerIsRejected() = assertRejected(
        """
        @Get("/a")
        @Cacheable(name = "c", key = "fixed")
        suspend fun a(): Reply = Reply("a")

        @Cacheable(name = "c", key = "helper")
        suspend fun helper(): Reply = Reply("h")
        """,
        "helper",
        "no HTTP method annotation",
    )

    @Test
    fun lockOnNonRouteMethodIsRejected() = assertRejected(
        """
        @Get("/a")
        suspend fun a(): Reply = Reply("a")

        @Lock(key = "helper")
        suspend fun helper(): Reply = Reply("h")
        """,
        "Neton @Lock",
        "helper",
    )

    // ---- 返回类型 ----

    @Test
    fun cacheableOnUnitReturnIsRejected() = assertRejected(
        """
        @Get("/a/{id}")
        @Cacheable(name = "c", key = "{id}")
        suspend fun a(id: Long) {}
        """,
        "cannot be",
        "cached",
    )

    @Test
    fun cacheEvictOnUnitReturnIsAccepted() = assertAccepted(
        """
        @Get("/a/{id}")
        @CacheEvict(name = "c", key = "{id}")
        suspend fun a(id: Long) {}
        """,
    )

    @Test
    fun cacheEvictAllEntriesNeedsNoKey() = assertAccepted(
        """
        @Post("/a")
        @CacheEvict(name = "c", allEntries = true)
        suspend fun a(payload: Payload) {}
        """,
    )

    // ---- @Lock 的 key 走同一套校验 ----

    @Test
    fun lockKeyWithUnknownPlaceholderIsRejected() = assertRejected(
        """
        @Get("/a/{id}")
        @Lock(key = "order:{orderId}")
        suspend fun a(id: Long): Reply = Reply("x")
        """,
        "Neton @Lock",
        "{orderId}",
    )

    @Test
    fun lockKeyWithPathPlaceholderIsAccepted() = assertAccepted(
        """
        @Get("/a/{id}")
        @Lock(key = "order:{id}")
        suspend fun a(id: Long): Reply = Reply("x")
        """,
    )

    // ---- 生成的代码本身 ----

    @Test
    fun generatedHandlerHashesTheBindingNameNotTheParameterName() {
        val compiled = compile(
            """
            @Get("/a/{id}")
            @Cacheable(name = "c")
            suspend fun a(@PathVariable("id") userId: Long): Reply = Reply("x")
            """,
        )
        assertTrue(compiled.generatedSource.isNotBlank(), "应当生成路由初始化器")
        assertContains(compiled.generatedSource, """stableHash(args, listOf("id"))""")
        assertFalse(
            compiled.generatedSource.contains(""""userId""""),
            "hash 不能用 Kotlin 参数名：\n${compiled.generatedSource}",
        )
    }

    @Test
    fun generatedHandlerResolvesLockKeyFromArgs() {
        val compiled = compile(
            """
            @Get("/a/{id}")
            @Lock(key = "order:{id}")
            suspend fun a(id: Long): Reply = Reply("x")
            """,
        )
        assertContains(compiled.generatedSource, """args.first("id")""")
        assertContains(compiled.generatedSource, "lockManager.withLock")
    }
}
