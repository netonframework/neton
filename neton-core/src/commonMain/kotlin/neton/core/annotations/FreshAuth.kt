package neton.core.annotations

/**
 * 标记控制器或方法需要"新鲜"的认证状态（spec TOKEN_UNIFICATION_SPEC v1.3 §10 · Fresh Authentication Required）。
 *
 * 行为定义（Phase B `security.unified_token.enabled=true` 时生效）：
 * - 该路由命中时**绕过** 30s session_version cache，强制走 `introspect` 实时校验
 * - 用于风险动作（改密、改手机、风控敏感写入等）：拒绝 token 在 30s 窗口内被吊销但仍能访问
 *
 * Phase A 默认 dormant：[neton.core.interfaces.RouteDefinition.freshAuth] 字段会被 KSP 写入，
 * 但运行时 dispatcher / authenticator 不消费它；行为与未启用 unified token 时完全等价。
 *
 * **不是** RBAC：与 [Permission] / [RequireAuth] 正交。`@FreshAuth` 表达的是
 * "认证状态必须新鲜"（cache 策略），不参与权限授权。
 *
 * 同时支持类级与方法级（与 [AllowAnonymous] 一致）：类级标注作用于该 controller 所有路由，
 * 方法级覆盖更细粒度。
 */
annotation class FreshAuth
