package neton.database.dsl

/**
 * where { } 块内 DSL，支持 whenPresent / whenNotBlank / whenNotEmpty。
 * 返回 Predicate.True 表示不追加条件。
 */
class PredicateScope {
    /** 无条件，查全部：where { all() } 表示不追加 WHERE */
    fun all(): Predicate = Predicate.True

    fun and(vararg ps: Predicate): Predicate =
        Predicate.And(ps.filterNot { it is Predicate.True }.toList())

    fun or(vararg ps: Predicate): Predicate =
        Predicate.Or(ps.filterNot { it is Predicate.True }.toList())

    inline fun <V> whenPresent(v: V?, block: (V) -> Predicate): Predicate =
        if (v != null) block(v) else Predicate.True

    inline fun whenNotBlank(v: String?, block: (String) -> Predicate): Predicate =
        if (!v.isNullOrBlank()) block(v) else Predicate.True

    inline fun <V> whenNotEmpty(v: Collection<V>?, block: (Collection<V>) -> Predicate): Predicate =
        if (!v.isNullOrEmpty()) block(v) else Predicate.True
}
