package neton.database.dsl

import neton.database.api.Row

/** 从多行结果中提取一对多关系（显式 key 去重）*/
inline fun <T, R, K> List<Row>.firstOneToMany(
    one: (Row) -> T,
    many: (Row) -> R?,
    manyKey: (R) -> K           // 显式 key 去重，不依赖 equals
): Pair<T, List<R>>? {
    if (isEmpty()) return null
    val entity = one(first())
    val related = mapNotNull(many).distinctBy(manyKey)
    return entity to related
}

/** 按 key 分组后提取一对多 */
inline fun <K, T, R, RK> List<Row>.groupOneToMany(
    key: (Row) -> K,
    one: (Row) -> T,
    many: (Row) -> R?,
    manyKey: (R) -> RK          // 显式 key 去重
): List<Pair<T, List<R>>> {
    return groupBy(key).map { (_, rows) ->
        val entity = one(rows.first())
        val related = rows.mapNotNull(many).distinctBy(manyKey)
        entity to related
    }
}
