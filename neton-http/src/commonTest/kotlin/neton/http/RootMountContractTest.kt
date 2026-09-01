package neton.http

import kotlin.test.Test
import kotlin.test.assertEquals
import neton.http.adapter.BufferedHttpDispatcher.Companion.joinPath

/**
 * 契约：路由组挂载路径拼接不产生双斜杠、不丢路径。
 * NewGate 的 gateway 组挂载 "/"（独占 /v1 前缀）依赖此行为。
 *
 * 直接测 BufferedHttpDispatcher.joinPath —— 装配时真正拼接挂载路径的那一个。
 */
class RootMountContractTest {

    @Test
    fun rootMountJoinsWithoutDoubleSlash() {
        assertEquals("/v1/models", joinPath("/", "/v1/models"))
        assertEquals("/v1/chat/completions", joinPath("/", "/v1/chat/completions"))
    }

    @Test
    fun normalMountJoinsWithSingleSlash() {
        assertEquals("/admin/user/list", joinPath("/admin", "/user/list"))
        assertEquals("/admin/user/list", joinPath("/admin/", "/user/list"))
    }

    @Test
    fun emptyPatternsResolveToMountRoot() {
        assertEquals("/", joinPath("/", "/"))
        assertEquals("/", joinPath("/", ""))
        assertEquals("/admin", joinPath("/admin", "/"))
        assertEquals("/admin", joinPath("/admin", ""))
    }
}
