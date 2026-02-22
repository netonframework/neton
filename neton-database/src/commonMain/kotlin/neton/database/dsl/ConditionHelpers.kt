package neton.database.dsl

/**
 * 条件筛选辅助函数（Phase 2）。
 * 用于 v1 ColumnPredicate（JOIN 场景），与 Phase 1 的 PredicateScope 独立。
 */

inline fun <V : Any> whenPresent(value: V?, block: (V) -> ColumnPredicate): ColumnPredicate =
    if (value != null) block(value) else ColumnPredicate.True

inline fun whenNotBlank(value: String?, block: (String) -> ColumnPredicate): ColumnPredicate =
    if (!value.isNullOrBlank()) block(value) else ColumnPredicate.True

inline fun <V> whenNotEmpty(value: Collection<V>?, block: (Collection<V>) -> ColumnPredicate): ColumnPredicate =
    if (!value.isNullOrEmpty()) block(value) else ColumnPredicate.True

fun allOf(vararg predicates: ColumnPredicate): ColumnPredicate {
    val filtered = predicates.filter { it !is ColumnPredicate.True }
    return when (filtered.size) {
        0 -> ColumnPredicate.True
        1 -> filtered.first()
        else -> ColumnPredicate.And(filtered)
    }
}

fun anyOf(vararg predicates: ColumnPredicate): ColumnPredicate {
    val filtered = predicates.filter { it !is ColumnPredicate.True }
    return when (filtered.size) {
        0 -> ColumnPredicate.True
        1 -> filtered.first()
        else -> ColumnPredicate.Or(filtered)
    }
}
