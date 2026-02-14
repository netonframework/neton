package neton.routing.annotations

import neton.core.http.HttpMethod

/**
 * 标记控制器方法的路由信息
 * @param method HTTP 方法
 * @param path 路径，可选，默认根据方法名生成
 */
annotation class Route(
    val method: HttpMethod = HttpMethod.GET,
    val path: String = ""
) 