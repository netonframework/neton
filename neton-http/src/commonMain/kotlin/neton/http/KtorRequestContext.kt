package neton.http

import neton.core.interfaces.RequestContext

/**
 * Ktor 请求上下文适配器 - 将请求信息转为安全层 RequestContext
 * v1 最小：method/path/headers/routeGroup；body 暂不读
 */
internal class KtorRequestContext(
    override val path: String,
    override val method: String,
    override val headers: Map<String, String>,
    override val routeGroup: String? = null
) : RequestContext
