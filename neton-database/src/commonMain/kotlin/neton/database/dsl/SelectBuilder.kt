package neton.database.dsl

import neton.database.api.DbContext
import neton.database.api.Table
import neton.database.api.readQualified

/**
 * 流式 SELECT 构建器。alias 自动分配（t1, t2, t3...），用户不手写。
 *
 * 用法：
 *   val (q, U) = db.from(SystemUserTable)                     // t1
 *   val UR = q.leftJoin(UserRoleTable).on { U.id eq it.userId }  // t2
 *   val R  = q.leftJoin(RoleTable).on { UR.roleId eq it.id }     // t3
 *
 *   q.where(U.status eq 1)
 *    .selectRows(U.id, U.username, R.name)
 *    .fetchRows()
 */
class SelectBuilder internal constructor(
    internal val db: DbContext    // ★ Phase 4：绑定执行上下文，由 DbContext.from() 注入
) {
    private var aliasCounter = 0
    private var fromRef: TableRefInfo? = null
    private val joins = mutableListOf<JoinClause>()
    private var where: ColumnPredicate? = null
    private val orderBy = mutableListOf<ColumnOrdering>()
    private val groupBy = mutableListOf<ProjectionExpr.Col>()
    private var having: ColumnPredicate? = null
    private var limit: Int? = null
    private var offset: Int? = null
    private var distinct: Boolean = false

    private data class TableRefInfo(val tableName: String, val alias: String)

    private fun nextAlias(): String = "t${++aliasCounter}"

    // ===== FROM =====
    internal fun <T : Any> from(table: Table<T, *>): TableRef<T> {
        val def = TableDefRegistry.get<T>(table)
        val alias = nextAlias()
        fromRef = TableRefInfo(def.tableName, alias)
        return TableRef(def, alias)
    }

    // ===== JOIN =====
    fun <T : Any> leftJoin(table: Table<T, *>): JoinStep<T> = joinStep(JoinType.LEFT, table)
    fun <T : Any> innerJoin(table: Table<T, *>): JoinStep<T> = joinStep(JoinType.INNER, table)
    fun <T : Any> rightJoin(table: Table<T, *>): JoinStep<T> = joinStep(JoinType.RIGHT, table)

    private fun <T : Any> joinStep(type: JoinType, table: Table<T, *>): JoinStep<T> {
        val def = TableDefRegistry.get<T>(table)
        val alias = nextAlias()
        return JoinStep(this, type, def.tableName, alias, TableRef(def, alias))
    }

    internal fun addJoin(clause: JoinClause) { joins.add(clause) }

    // ===== WHERE =====
    fun where(predicate: ColumnPredicate): SelectBuilder = apply { this.where = predicate }

    // ===== ORDER BY =====
    fun orderBy(vararg orderings: ColumnOrdering): SelectBuilder = apply {
        this.orderBy.addAll(orderings)
    }

    // ===== GROUP BY / HAVING =====
    fun groupBy(vararg cols: ColRef<*, *>): SelectBuilder = apply {
        this.groupBy.addAll(cols.map { ProjectionExpr.Col(it.alias, it.columnName) })
    }
    fun having(predicate: ColumnPredicate): SelectBuilder = apply { this.having = predicate }

    // ===== LIMIT / OFFSET =====
    fun limit(n: Int): SelectBuilder = apply { this.limit = n }
    fun offset(n: Int): SelectBuilder = apply { this.offset = n }
    fun distinct(): SelectBuilder = apply { this.distinct = true }

    // ===== SELECT（Row 逃生口）=====
    fun selectRows(vararg cols: ColRef<*, *>): ProjectedSelect =
        ProjectedSelect(db, buildAst(cols.map { ProjectionExpr.Col(it.alias, it.columnName) }))

    fun selectAllRows(): ProjectedSelect =
        ProjectedSelect(db, buildAst(emptyList()))

    // ===== SELECT（Typed Projection，路径 B）=====
    fun <A> select(c1: ColRef<*, A>): TypedProjectedSelect1<A> {
        val key1 = "${c1.alias}_${c1.columnName}"
        return TypedProjectedSelect1(
            db = db,
            ast = buildAst(listOf(ProjectionExpr.Col(c1.alias, c1.columnName))),
            read1 = { row -> row.readQualified(key1, c1.column) }
        )
    }

    fun <A, B> select(c1: ColRef<*, A>, c2: ColRef<*, B>): TypedProjectedSelect2<A, B> {
        val key1 = "${c1.alias}_${c1.columnName}"
        val key2 = "${c2.alias}_${c2.columnName}"
        return TypedProjectedSelect2(
            db = db,
            ast = buildAst(listOf(
                ProjectionExpr.Col(c1.alias, c1.columnName),
                ProjectionExpr.Col(c2.alias, c2.columnName)
            )),
            read1 = { row -> row.readQualified(key1, c1.column) },
            read2 = { row -> row.readQualified(key2, c2.column) }
        )
    }

    fun <A, B, C> select(c1: ColRef<*, A>, c2: ColRef<*, B>, c3: ColRef<*, C>): TypedProjectedSelect3<A, B, C> {
        val key1 = "${c1.alias}_${c1.columnName}"
        val key2 = "${c2.alias}_${c2.columnName}"
        val key3 = "${c3.alias}_${c3.columnName}"
        return TypedProjectedSelect3(
            db = db,
            ast = buildAst(listOf(
                ProjectionExpr.Col(c1.alias, c1.columnName),
                ProjectionExpr.Col(c2.alias, c2.columnName),
                ProjectionExpr.Col(c3.alias, c3.columnName)
            )),
            read1 = { row -> row.readQualified(key1, c1.column) },
            read2 = { row -> row.readQualified(key2, c2.column) },
            read3 = { row -> row.readQualified(key3, c3.column) }
        )
    }

    fun <A, B, C, D> select(c1: ColRef<*, A>, c2: ColRef<*, B>, c3: ColRef<*, C>, c4: ColRef<*, D>): TypedProjectedSelect4<A, B, C, D> {
        val key1 = "${c1.alias}_${c1.columnName}"
        val key2 = "${c2.alias}_${c2.columnName}"
        val key3 = "${c3.alias}_${c3.columnName}"
        val key4 = "${c4.alias}_${c4.columnName}"
        return TypedProjectedSelect4(
            db = db,
            ast = buildAst(listOf(
                ProjectionExpr.Col(c1.alias, c1.columnName),
                ProjectionExpr.Col(c2.alias, c2.columnName),
                ProjectionExpr.Col(c3.alias, c3.columnName),
                ProjectionExpr.Col(c4.alias, c4.columnName)
            )),
            read1 = { row -> row.readQualified(key1, c1.column) },
            read2 = { row -> row.readQualified(key2, c2.column) },
            read3 = { row -> row.readQualified(key3, c3.column) },
            read4 = { row -> row.readQualified(key4, c4.column) }
        )
    }

    fun <A, B, C, D, E> select(c1: ColRef<*, A>, c2: ColRef<*, B>, c3: ColRef<*, C>, c4: ColRef<*, D>, c5: ColRef<*, E>): TypedProjectedSelect5<A, B, C, D, E> {
        val key1 = "${c1.alias}_${c1.columnName}"
        val key2 = "${c2.alias}_${c2.columnName}"
        val key3 = "${c3.alias}_${c3.columnName}"
        val key4 = "${c4.alias}_${c4.columnName}"
        val key5 = "${c5.alias}_${c5.columnName}"
        return TypedProjectedSelect5(
            db = db,
            ast = buildAst(listOf(
                ProjectionExpr.Col(c1.alias, c1.columnName),
                ProjectionExpr.Col(c2.alias, c2.columnName),
                ProjectionExpr.Col(c3.alias, c3.columnName),
                ProjectionExpr.Col(c4.alias, c4.columnName),
                ProjectionExpr.Col(c5.alias, c5.columnName)
            )),
            read1 = { row -> row.readQualified(key1, c1.column) },
            read2 = { row -> row.readQualified(key2, c2.column) },
            read3 = { row -> row.readQualified(key3, c3.column) },
            read4 = { row -> row.readQualified(key4, c4.column) },
            read5 = { row -> row.readQualified(key5, c5.column) }
        )
    }

    fun <A, B, C, D, E, F> select(c1: ColRef<*, A>, c2: ColRef<*, B>, c3: ColRef<*, C>, c4: ColRef<*, D>, c5: ColRef<*, E>, c6: ColRef<*, F>): TypedProjectedSelect6<A, B, C, D, E, F> {
        val key1 = "${c1.alias}_${c1.columnName}"
        val key2 = "${c2.alias}_${c2.columnName}"
        val key3 = "${c3.alias}_${c3.columnName}"
        val key4 = "${c4.alias}_${c4.columnName}"
        val key5 = "${c5.alias}_${c5.columnName}"
        val key6 = "${c6.alias}_${c6.columnName}"
        return TypedProjectedSelect6(
            db = db,
            ast = buildAst(listOf(
                ProjectionExpr.Col(c1.alias, c1.columnName),
                ProjectionExpr.Col(c2.alias, c2.columnName),
                ProjectionExpr.Col(c3.alias, c3.columnName),
                ProjectionExpr.Col(c4.alias, c4.columnName),
                ProjectionExpr.Col(c5.alias, c5.columnName),
                ProjectionExpr.Col(c6.alias, c6.columnName)
            )),
            read1 = { row -> row.readQualified(key1, c1.column) },
            read2 = { row -> row.readQualified(key2, c2.column) },
            read3 = { row -> row.readQualified(key3, c3.column) },
            read4 = { row -> row.readQualified(key4, c4.column) },
            read5 = { row -> row.readQualified(key5, c5.column) },
            read6 = { row -> row.readQualified(key6, c6.column) }
        )
    }

    fun <A, B, C, D, E, F, G> select(c1: ColRef<*, A>, c2: ColRef<*, B>, c3: ColRef<*, C>, c4: ColRef<*, D>, c5: ColRef<*, E>, c6: ColRef<*, F>, c7: ColRef<*, G>): TypedProjectedSelect7<A, B, C, D, E, F, G> {
        val key1 = "${c1.alias}_${c1.columnName}"
        val key2 = "${c2.alias}_${c2.columnName}"
        val key3 = "${c3.alias}_${c3.columnName}"
        val key4 = "${c4.alias}_${c4.columnName}"
        val key5 = "${c5.alias}_${c5.columnName}"
        val key6 = "${c6.alias}_${c6.columnName}"
        val key7 = "${c7.alias}_${c7.columnName}"
        return TypedProjectedSelect7(
            db = db,
            ast = buildAst(listOf(
                ProjectionExpr.Col(c1.alias, c1.columnName),
                ProjectionExpr.Col(c2.alias, c2.columnName),
                ProjectionExpr.Col(c3.alias, c3.columnName),
                ProjectionExpr.Col(c4.alias, c4.columnName),
                ProjectionExpr.Col(c5.alias, c5.columnName),
                ProjectionExpr.Col(c6.alias, c6.columnName),
                ProjectionExpr.Col(c7.alias, c7.columnName)
            )),
            read1 = { row -> row.readQualified(key1, c1.column) },
            read2 = { row -> row.readQualified(key2, c2.column) },
            read3 = { row -> row.readQualified(key3, c3.column) },
            read4 = { row -> row.readQualified(key4, c4.column) },
            read5 = { row -> row.readQualified(key5, c5.column) },
            read6 = { row -> row.readQualified(key6, c6.column) },
            read7 = { row -> row.readQualified(key7, c7.column) }
        )
    }

    fun <A, B, C, D, E, F, G, H> select(c1: ColRef<*, A>, c2: ColRef<*, B>, c3: ColRef<*, C>, c4: ColRef<*, D>, c5: ColRef<*, E>, c6: ColRef<*, F>, c7: ColRef<*, G>, c8: ColRef<*, H>): TypedProjectedSelect8<A, B, C, D, E, F, G, H> {
        val key1 = "${c1.alias}_${c1.columnName}"
        val key2 = "${c2.alias}_${c2.columnName}"
        val key3 = "${c3.alias}_${c3.columnName}"
        val key4 = "${c4.alias}_${c4.columnName}"
        val key5 = "${c5.alias}_${c5.columnName}"
        val key6 = "${c6.alias}_${c6.columnName}"
        val key7 = "${c7.alias}_${c7.columnName}"
        val key8 = "${c8.alias}_${c8.columnName}"
        return TypedProjectedSelect8(
            db = db,
            ast = buildAst(listOf(
                ProjectionExpr.Col(c1.alias, c1.columnName),
                ProjectionExpr.Col(c2.alias, c2.columnName),
                ProjectionExpr.Col(c3.alias, c3.columnName),
                ProjectionExpr.Col(c4.alias, c4.columnName),
                ProjectionExpr.Col(c5.alias, c5.columnName),
                ProjectionExpr.Col(c6.alias, c6.columnName),
                ProjectionExpr.Col(c7.alias, c7.columnName),
                ProjectionExpr.Col(c8.alias, c8.columnName)
            )),
            read1 = { row -> row.readQualified(key1, c1.column) },
            read2 = { row -> row.readQualified(key2, c2.column) },
            read3 = { row -> row.readQualified(key3, c3.column) },
            read4 = { row -> row.readQualified(key4, c4.column) },
            read5 = { row -> row.readQualified(key5, c5.column) },
            read6 = { row -> row.readQualified(key6, c6.column) },
            read7 = { row -> row.readQualified(key7, c7.column) },
            read8 = { row -> row.readQualified(key8, c8.column) }
        )
    }

    // ===== BUILD AST =====
    internal fun buildAst(projection: List<ProjectionExpr>): SelectAst {
        val f = fromRef ?: throw IllegalStateException("from() not called")
        return SelectAst(
            fromTable = f.tableName, fromAlias = f.alias,
            joins = joins.toList(), where = where,
            orderBy = orderBy.toList(), projection = projection,
            groupBy = groupBy.toList(), having = having,
            limit = limit, offset = offset, distinct = distinct
        )
    }
}

class JoinStep<T : Any> internal constructor(
    private val builder: SelectBuilder,
    private val type: JoinType,
    private val tableName: String,
    private val alias: String,
    private val ref: TableRef<T>
) {
    /**
     * on 接收一个 lambda，lambda 的参数 `it` 就是被 JOIN 表的 TableRef。
     * 用户通过 `it.prop`（KSP 生成的扩展属性）获得被 JOIN 表的列引用。
     * 返回 TableRef<T>（被 JOIN 表），用户赋值给变量后续使用。
     *
     * 示例：
     *   val UR = q.leftJoin(UserRoleTable).on { U.id eq it.userId }
     *   val R  = q.leftJoin(RoleTable).on { UR.roleId eq it.id }
     */
    fun on(block: (TableRef<T>) -> JoinCondition): TableRef<T> {
        val condition = block(ref)
        builder.addJoin(JoinClause(type, tableName, alias, condition))
        return ref
    }
}
