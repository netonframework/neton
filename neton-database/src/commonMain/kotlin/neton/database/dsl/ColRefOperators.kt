package neton.database.dsl

// ===== 值比较（强类型：V 必须匹配）=====
infix fun <T : Any, V> ColRef<T, V>.eq(value: V): ColumnPredicate =
    ColumnPredicate.Eq(this.alias, this.columnName, value)

infix fun <T : Any, V> ColRef<T, V>.ne(value: V): ColumnPredicate =
    ColumnPredicate.Ne(this.alias, this.columnName, value)

infix fun <T : Any, V : Comparable<V>> ColRef<T, V>.gt(value: V): ColumnPredicate =
    ColumnPredicate.Gt(this.alias, this.columnName, value)

infix fun <T : Any, V : Comparable<V>> ColRef<T, V>.ge(value: V): ColumnPredicate =
    ColumnPredicate.Ge(this.alias, this.columnName, value)

infix fun <T : Any, V : Comparable<V>> ColRef<T, V>.lt(value: V): ColumnPredicate =
    ColumnPredicate.Lt(this.alias, this.columnName, value)

infix fun <T : Any, V : Comparable<V>> ColRef<T, V>.le(value: V): ColumnPredicate =
    ColumnPredicate.Le(this.alias, this.columnName, value)

infix fun <T : Any> ColRef<T, String>.like(pattern: String): ColumnPredicate =
    ColumnPredicate.Like(this.alias, this.columnName, pattern)

infix fun <T : Any, V> ColRef<T, V>.`in`(values: Collection<V>): ColumnPredicate =
    ColumnPredicate.In(this.alias, this.columnName, values.toList())

fun <T : Any, V> ColRef<T, V>.isNull(): ColumnPredicate =
    ColumnPredicate.IsNull(this.alias, this.columnName)

fun <T : Any, V> ColRef<T, V>.isNotNull(): ColumnPredicate =
    ColumnPredicate.IsNotNull(this.alias, this.columnName)

infix fun <T : Any, V : Comparable<V>> ColRef<T, V>.between(range: Pair<V, V>): ColumnPredicate =
    ColumnPredicate.Between(this.alias, this.columnName, range.first, range.second)

// ===== 排序 =====
fun <T : Any, V> ColRef<T, V>.asc(): ColumnOrdering =
    ColumnOrdering(this.alias, this.columnName, Dir.ASC)

fun <T : Any, V> ColRef<T, V>.desc(): ColumnOrdering =
    ColumnOrdering(this.alias, this.columnName, Dir.DESC)

// ===== 跨表 JOIN 条件（左列 = 右列）=====
// SQL JOIN 不关心 nullability，提供多个重载支持所有组合

/** JOIN 条件：非 nullable 类型（要求类型完全一致） */
infix fun <T : Any, R : Any, V> ColRef<T, V>.eq(other: ColRef<R, V>): JoinCondition =
    JoinCondition(
        leftAlias = this.alias, leftColumn = this.columnName,
        rightAlias = other.alias, rightColumn = other.columnName
    )

/** JOIN 条件：左 nullable + 右非 nullable（如 User.id? eq UserRole.userId） */
infix fun <T : Any, R : Any, V : Any> ColRef<T, V?>.eq(other: ColRef<R, V>): JoinCondition =
    JoinCondition(
        leftAlias = this.alias, leftColumn = this.columnName,
        rightAlias = other.alias, rightColumn = other.columnName
    )

/** JOIN 条件：左非 nullable + 右 nullable（如 UserRole.userId eq User.id?） */
infix fun <T : Any, R : Any, V : Any> ColRef<T, V>.eq(other: ColRef<R, V?>): JoinCondition =
    JoinCondition(
        leftAlias = this.alias, leftColumn = this.columnName,
        rightAlias = other.alias, rightColumn = other.columnName
    )

/** JOIN 条件：双方都 nullable */
infix fun <T : Any, R : Any, V : Any> ColRef<T, V?>.eq(other: ColRef<R, V?>): JoinCondition =
    JoinCondition(
        leftAlias = this.alias, leftColumn = this.columnName,
        rightAlias = other.alias, rightColumn = other.columnName
    )
