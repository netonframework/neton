package neton.database.dsl

import kotlin.reflect.KClass
import kotlin.reflect.KProperty1

/**
 * TableDef 注册表（internal）。
 * 存储 KClass → TableDef 和 Table → TableDef 的映射。
 * 在 DatabaseComponent.init() 一次性注册（KSP 生成注册代码），
 * 运行时只读查找，O(1)。禁止 resolve 时反射扫描。
 */
internal object TableDefRegistry {
    private val byClass = mutableMapOf<KClass<*>, TableDef<*>>()
    private val byTable = mutableMapOf<Any, TableDef<*>>()   // Table<T, ID> → TableDef<T>

    /** DatabaseComponent.init() 内调用，KSP 生成 */
    fun <T : Any> register(klass: KClass<T>, table: Any, def: TableDef<T>) {
        byClass[klass] = def
        byTable[table] = def
    }

    /** 通过 Table 实例查找（SelectBuilder.from / joinStep 内部使用） */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(table: Any): TableDef<T> =
        byTable[table] as? TableDef<T>
            ?: throw IllegalStateException("No TableDef registered for $table. Ensure @Table is annotated and DatabaseComponent is initialized.")

    /**
     * 通过 KClass + KProperty1 解析列（intoOrNull / Row.get 内部使用）。
     * 标记为 public 以允许 inline 函数调用，但 TableDefRegistry 本身仍是 internal。
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any, V> resolve(klass: KClass<T>, prop: KProperty1<T, V>): Column<T, V> {
        val def = byClass[klass] as? TableDef<T>
            ?: throw IllegalStateException("No TableDef for ${klass.simpleName}")
        return def.resolve(prop)
    }

    // ⚠️ find(prop) 已废除（原则 15 加固）
    // 遍历 byClass.values 再找 propertyName 存在 O(n) 与同名字段误匹配风险。
    // 正式路径必须走 resolve(entityClass, prop)：单表 query 已知 Table 的实体类型/def，
    // 直接 resolve，不需要 find。find 仅保留用于调试（标 @Deprecated）。
    @Deprecated("Use resolve(klass, prop) instead. find() is O(n) and may mismatch same-name properties across entities.")
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> find(prop: KProperty1<T, *>): TableDef<T>? {
        return byClass.values.firstOrNull { def ->
            def.columns.any { it.propertyName == prop.name }
        } as? TableDef<T>
    }
}
