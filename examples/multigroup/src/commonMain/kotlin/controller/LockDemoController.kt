package controller

import neton.core.annotations.Controller
import neton.core.annotations.Get
import neton.core.annotations.PathVariable
import neton.redis.lock.Lock

/**
 * 分布式锁演示：@Lock 由 KSP 织入，同一 resourceId 并发请求第二个返回 409。
 * 锁 key 为 neton:lock:demo:{resourceId}（keyPrefix + lock 前缀 + 业务 key）。
 */
@Controller
class LockDemoController {

    @Get("/api/lock/{resourceId}")
    @Lock(key = "demo:{resourceId}", ttlMs = 10_000, waitMs = 0)
    suspend fun lockDemo(@PathVariable resourceId: String): String {
        return """{"ok":true,"resourceId":"$resourceId","message":"Lock acquired"}"""
    }
}
