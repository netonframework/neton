package neton.storage

interface StorageManager {
    /** 获取指定名称的存储源；不存在则抛 IllegalStateException */
    fun get(name: String): StorageOperator

    /** 获取默认存储源（等价于 get("default")） */
    fun default(): StorageOperator

    /** 所有已注册的源名称 */
    fun names(): Set<String>
}
