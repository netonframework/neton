package neton.core.annotations

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * @FreshAuth 注解契约测试（spec TOKEN_UNIFICATION_SPEC v1.3 Phase A · Step 5）。
 *
 * **测试方式：编译期 fixture**。
 * 下面的 fixture 把 [FreshAuth] 同时放在 CLASS 和 FUNCTION 两种位置；
 * 如果以后有人给 [FreshAuth] 加 `@Target(...)` 把目标缩窄到只剩一种，本文件**编译失败**——
 * 这就是 Step 5 对 "支持 CLASS+FUNCTION" 的契约锁。
 *
 * 不用反射断言：Kotlin/Native commonTest 没有 `KClass.annotations` / Java reflection；
 * 而 RouteDefinition 字段消费侧的 dormant 行为已由 `neton-http` 的
 * `SecurityPipelineContractTest` 覆盖（默认 false / 直传 true / Phase A 行为不变）。
 */
class FreshAuthAnnotationTest {

    @FreshAuth
    private class ClassLevelFreshController {
        fun anyRoute(): String = "ok"
    }

    private class MethodLevelFreshController {
        @FreshAuth
        fun freshRoute(): String = "ok"

        fun normalRoute(): String = "ok"
    }

    @Test
    fun fixturesCompile_lockingClassAndFunctionTargets() {
        // fixture 实例化触发 class loading；真正的契约在编译期就已锁住。
        // 这里只做一个 smoke：fixtures 能跑就说明两个 target 注解位置都通过编译。
        val classLevel = ClassLevelFreshController()
        val methodLevel = MethodLevelFreshController()
        assertEquals("ok", classLevel.anyRoute())
        assertEquals("ok", methodLevel.freshRoute())
        assertEquals("ok", methodLevel.normalRoute())
    }
}
