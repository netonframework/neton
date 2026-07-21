package neton.http

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 契约：路由组挂载路径拼接不产生双斜杠、不丢路径。
 * NewGate 的 gateway 组挂载 "/"（独占 /v1/*）依赖此行为。
 */
class RootMountContractTest {

    @Test
    fun rootMountJoinsWithoutDoubleSlash() {
        assertEquals("/v1/models", joinMountPath("/", "/v1/models"))
        assertEquals("/v1/chat/completions", joinMountPath("/", "/v1/chat/completions"))
    }

    @Test
    fun normalMountJoinsWithSingleSlash() {
        assertEquals("/admin/user/list", joinMountPath("/admin", "/user/list"))
        assertEquals("/admin/user/list", joinMountPath("/admin/", "/user/list"))
    }

    @Test
    fun emptyPatternsResolveToMountRoot() {
        assertEquals("/", joinMountPath("/", "/"))
        assertEquals("/", joinMountPath("/", ""))
        assertEquals("/admin", joinMountPath("/admin", "/"))
        assertEquals("/admin", joinMountPath("/admin", ""))
    }
}
