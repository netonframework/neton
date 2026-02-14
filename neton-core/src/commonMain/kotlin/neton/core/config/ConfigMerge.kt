package neton.core.config

/**
 * 配置合并（Neton-Core-Spec 5.3）。
 * table 深度合并，数组整体覆盖。
 */
object ConfigMerge {

    /**
     * 深度合并：override 中同 key 的 table 递归合并，否则（含数组）整体覆盖。
     */
    @Suppress("UNCHECKED_CAST")
    fun merge(base: Map<String, Any?>, override: Map<String, Any?>): Map<String, Any?> {
        val result = base.toMutableMap()
        for ((k, ov) in override) {
            val bv = result[k]
            result[k] = when {
                ov is Map<*, *> && bv is Map<*, *> ->
                    merge(bv as Map<String, Any?>, ov as Map<String, Any?>)
                else -> ov
            }
        }
        return result
    }
}
