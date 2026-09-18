package neton.routing.ratelimit

/**
 * 限流用的「客户端 IP」判定。纯函数，规则与 `HttpRequest.peerAddress` 的文档一致：
 *
 * - 对端不是可信代理 → 就是对端本身。`X-Forwarded-For` 由客户端自由填写，直连场景下采信它
 *   等于让每个请求自带一个「我是谁」——限流按 IP 计就形同虚设。
 * - 对端是可信代理 → 从 `X-Forwarded-For` **右往左**剥掉每一跳可信代理，第一个不可信的就是客户端；
 *   整条链都可信或头缺失时退回 `X-Real-IP`，再退回对端。
 *
 * 没配可信代理时永远返回对端：这是安全默认值。部署在反代之后必须配 `ratelimit.trusted_proxies`，
 * 否则所有客户端共用反代那一个桶——那会立刻在日志里表现为「所有人都被限流」，比伪造绕过好发现得多。
 */
object ClientIpResolver {

    fun resolve(
        peerAddress: String,
        forwardedFor: String?,
        realIp: String?,
        trustedProxies: Set<String>,
    ): String {
        val peer = stripPort(peerAddress)
        if (peer.isEmpty()) return "unknown"
        if (peer !in trustedProxies) return peer

        val hops = forwardedFor
            ?.split(',')
            ?.map { stripPort(it.trim()) }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        for (hop in hops.asReversed()) {
            if (hop !in trustedProxies) return hop
        }
        realIp?.trim()?.takeIf { it.isNotEmpty() }?.let { return stripPort(it) }
        return peer
    }

    /** `1.2.3.4:5678` / `[::1]:5678` → 去掉端口；纯 IPv6 不动。 */
    fun stripPort(address: String): String {
        val a = address.trim()
        if (a.startsWith("[")) {
            val end = a.indexOf(']')
            return if (end > 0) a.substring(1, end) else a
        }
        // 只有一个冒号才可能是 v4:port；多个冒号是裸 v6
        return if (a.count { it == ':' } == 1) a.substringBefore(':') else a
    }
}
