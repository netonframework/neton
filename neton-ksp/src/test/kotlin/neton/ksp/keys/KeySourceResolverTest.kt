package neton.ksp.keys

import neton.ksp.keys.KeySourceResolver.KeyParam
import neton.ksp.keys.KeySourceResolver.KeyProblem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `@Cacheable` / `@Lock` 的 key 来源判定。
 *
 * 这层判定错了不会崩，只会**悄悄错命中**：取不到值的参数在 args 里是 null，两个只有 body 不同
 * 的请求算出同一个 key，缓存就把 A 的响应返回给 B。所以这里逐条钉死「哪些参数能进 key」和
 * 「进 key 时用什么名字」——后者尤其容易错，args 的键是绑定名而不是 Kotlin 参数名。
 */
class KeySourceResolverTest {

    private fun resolve(
        params: List<KeyParam>,
        path: String = "/x",
        method: String = "GET",
    ) = KeySourceResolver.resolveArgsNames(params, KeySourceResolver.placeholdersOf(path).toSet(), method)

    private fun param(
        name: String,
        type: String = "kotlin.Long",
        annotations: Set<String> = emptySet(),
        pathAlias: String? = null,
        queryParamAlias: String? = null,
        queryAlias: String? = null,
        uploadList: Boolean = false,
    ) = KeyParam(name, type, annotations, pathAlias, queryParamAlias, queryAlias, uploadList)

    // ---- 绑定别名：args 的键是绑定名，不是 Kotlin 参数名 ----

    @Test
    fun pathVariableAliasBecomesTheArgsName() {
        val names = resolve(
            listOf(param("userId", annotations = setOf("PathVariable"), pathAlias = "id")),
            path = "/u/{id}",
        )
        assertEquals(mapOf("userId" to "id"), names)
    }

    @Test
    fun queryParamAliasBecomesTheArgsName() {
        val names = resolve(listOf(param("pageNo", "kotlin.Int", setOf("QueryParam"), queryParamAlias = "page")))
        assertEquals(mapOf("pageNo" to "page"), names)
    }

    @Test
    fun emptyQueryAliasFallsBackToParameterName() {
        // @Query 不带参数时注解值是空串，不能当成别名
        val names = resolve(listOf(param("tag", "kotlin.String", setOf("Query"), queryAlias = "")))
        assertEquals(mapOf("tag" to "tag"), names)
    }

    @Test
    fun keyMustUseTheBindingNameNotTheParameterName() {
        val names = resolve(
            listOf(param("userId", annotations = setOf("PathVariable"), pathAlias = "id")),
            path = "/u/{id}",
        )
        assertTrue(KeySourceResolver.validate("{id}", names).isEmpty(), "{id} 是真实的 args 键，应当放行")

        val problems = KeySourceResolver.validate("{userId}", names)
        val problem = assertIs<KeyProblem.UnresolvablePlaceholders>(problems.single())
        assertEquals(listOf("userId"), problem.placeholders)
        assertEquals(mapOf("userId" to "id"), problem.aliasHints, "应当直接告诉用户改写成哪个名字")
    }

    @Test
    fun hashKeyNamesUseBindingNames() {
        val names = resolve(
            listOf(param("userId", annotations = setOf("PathVariable"), pathAlias = "id")),
            path = "/u/{id}",
        )
        assertEquals(listOf("id"), KeySourceResolver.hashKeyNames(names))
    }

    // ---- 取不到值的来源 ----

    @Test
    fun bodyParameterIsNotAvailable() {
        val names = resolve(listOf(param("req", "com.example.Req")), method = "POST")
        assertEquals(mapOf("req" to null), names)
    }

    @Test
    fun explicitBodyAnnotationIsNotAvailable() {
        val names = resolve(listOf(param("req", "com.example.Req", setOf("Body"))), method = "PUT")
        assertEquals(mapOf("req" to null), names)
    }

    @Test
    fun headerCookieAndFormAreNotAvailable() {
        listOf("Header", "Cookie", "FormParam").forEach { annotation ->
            val names = resolve(listOf(param("v", "kotlin.String", setOf(annotation))))
            assertEquals(mapOf("v" to null), names, annotation)
        }
    }

    @Test
    fun injectedFrameworkTypesAreNotAvailable() {
        KeySourceResolver.INJECTED_PARAM_TYPES.forEach { type ->
            val names = resolve(listOf(param("p", type)))
            assertEquals(mapOf("p" to null), names, type)
        }
    }

    @Test
    fun uploadFileListIsNotAvailable() {
        val names = resolve(listOf(param("files", "kotlin.collections.List", uploadList = true)))
        assertEquals(mapOf("files" to null), names)
    }

    @Test
    fun currentUserIsNotAvailable() {
        val names = resolve(listOf(param("me", "com.example.User", setOf("CurrentUser"))))
        assertEquals(mapOf("me" to null), names)
    }

    // ---- 能取到值的来源 ----

    @Test
    fun pathParameterByConventionIsAvailable() {
        assertEquals(mapOf("id" to "id"), resolve(listOf(param("id")), path = "/u/{id}"))
    }

    @Test
    fun simpleTypeOnBodyMethodStillGoesToQuery() {
        // POST 上的简单类型按约定走 query，仍可进 key
        val names = resolve(listOf(param("name", "kotlin.String")), method = "POST")
        assertEquals(mapOf("name" to "name"), names)
    }

    @Test
    fun queryParameterOnGetIsAvailable() {
        assertEquals(mapOf("q" to "q"), resolve(listOf(param("q", "kotlin.String"))))
    }

    // ---- 默认 hash 的校验 ----

    @Test
    fun defaultHashRejectsUnusableParameters() {
        val names = resolve(
            listOf(param("id"), param("req", "com.example.Req")),
            path = "/u/{id}",
            method = "POST",
        )
        val problem = assertIs<KeyProblem.UnusableParams>(KeySourceResolver.validate("", names).single())
        assertEquals(listOf("req"), problem.kotlinNames)
        assertEquals(listOf("id"), problem.available)
    }

    @Test
    fun defaultHashAcceptsAllPathAndQueryParameters() {
        val names = resolve(listOf(param("id"), param("q", "kotlin.String")), path = "/u/{id}")
        assertTrue(KeySourceResolver.validate("", names).isEmpty())
        assertEquals(listOf("id", "q"), KeySourceResolver.hashKeyNames(names))
    }

    @Test
    fun handlerWithNoParametersIsFine() {
        assertTrue(KeySourceResolver.validate("", emptyMap()).isEmpty())
    }

    // ---- 模板校验 ----

    @Test
    fun nestedPlaceholderIsRejected() {
        val names = resolve(listOf(param("id")), path = "/u/{id}")
        val problem = assertIs<KeyProblem.UnresolvablePlaceholders>(
            KeySourceResolver.validate("{user.id}", names).single(),
        )
        assertEquals(listOf("user.id"), problem.placeholders)
        assertTrue(problem.aliasHints.isEmpty())
    }

    @Test
    fun constantTemplateWithoutPlaceholdersIsFine() {
        assertTrue(KeySourceResolver.validate("all-users", resolve(listOf(param("req", "com.example.Req"), ), method = "POST")).isEmpty())
    }

    @Test
    fun templateMayMixLiteralsAndPlaceholders() {
        val names = resolve(listOf(param("id"), param("q", "kotlin.String")), path = "/u/{id}")
        assertTrue(KeySourceResolver.validate("user:{id}:tag:{q}", names).isEmpty())
    }

    @Test
    fun onlyTheUnresolvablePlaceholdersAreReported() {
        val names = resolve(listOf(param("id")), path = "/u/{id}")
        val problem = assertIs<KeyProblem.UnresolvablePlaceholders>(
            KeySourceResolver.validate("{id}:{missing}", names).single(),
        )
        assertEquals(listOf("missing"), problem.placeholders)
    }

    // ---- 错误信息本身也是契约的一部分 ----

    @Test
    fun messagesNameTheOffenderAndTheFix() {
        val names = resolve(
            listOf(param("userId", annotations = setOf("PathVariable"), pathAlias = "id")),
            path = "/u/{id}",
        )
        val text = KeySourceResolver.describe(
            KeySourceResolver.validate("{userId}", names).single(),
            "@Cacheable",
            "getUser",
        )
        assertTrue(text.contains("@Cacheable"), text)
        assertTrue(text.contains("getUser"), text)
        assertTrue(text.contains("{userId}"), text)
        assertTrue(text.contains("use {id} instead of {userId}"), text)
    }
}
