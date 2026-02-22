package neton.database.api

import neton.database.dsl.*
import kotlin.reflect.KProperty1

/**
 * Phase 1 实体查询：由 query { } 产出，list/count/page 与 where 同源（SqlBuilder）。
 * Phase 3 扩展：新增 typed select 方法。
 */
interface EntityQuery<T : Any> {
    suspend fun list(): List<T>
    suspend fun count(): Long
    suspend fun page(page: Int, size: Int): Page<T>
    /** 指定列后变为投影查询，返回 Row */
    fun select(vararg columnNames: String): ProjectionQuery

    // Phase 3 新增：typed select（使用 KProperty1）
    fun <A> select(c1: KProperty1<T, A>): TypedProjection1<A>
    fun <A, B> select(c1: KProperty1<T, A>, c2: KProperty1<T, B>): TypedProjection2<A, B>
    fun <A, B, C> select(c1: KProperty1<T, A>, c2: KProperty1<T, B>, c3: KProperty1<T, C>): TypedProjection3<A, B, C>
    fun <A, B, C, D> select(c1: KProperty1<T, A>, c2: KProperty1<T, B>, c3: KProperty1<T, C>, c4: KProperty1<T, D>): TypedProjection4<A, B, C, D>
    fun <A, B, C, D, E> select(c1: KProperty1<T, A>, c2: KProperty1<T, B>, c3: KProperty1<T, C>, c4: KProperty1<T, D>, c5: KProperty1<T, E>): TypedProjection5<A, B, C, D, E>
    fun <A, B, C, D, E, F> select(c1: KProperty1<T, A>, c2: KProperty1<T, B>, c3: KProperty1<T, C>, c4: KProperty1<T, D>, c5: KProperty1<T, E>, c6: KProperty1<T, F>): TypedProjection6<A, B, C, D, E, F>
    fun <A, B, C, D, E, F, G> select(c1: KProperty1<T, A>, c2: KProperty1<T, B>, c3: KProperty1<T, C>, c4: KProperty1<T, D>, c5: KProperty1<T, E>, c6: KProperty1<T, F>, c7: KProperty1<T, G>): TypedProjection7<A, B, C, D, E, F, G>
    fun <A, B, C, D, E, F, G, H> select(c1: KProperty1<T, A>, c2: KProperty1<T, B>, c3: KProperty1<T, C>, c4: KProperty1<T, D>, c5: KProperty1<T, E>, c6: KProperty1<T, F>, c7: KProperty1<T, G>, c8: KProperty1<T, H>): TypedProjection8<A, B, C, D, E, F, G, H>
}

/**
 * Phase 1 投影查询：select(...) 后，rows/page 返回 Row，不泄漏 sqlx4k。
 */
interface ProjectionQuery {
    suspend fun rows(): List<Row>
    suspend fun count(): Long
    suspend fun page(page: Int, size: Int): Page<Row>
}

// Phase 3 Typed Projection 接口（单表 select，路径 A）
interface TypedProjection1<A> {
    suspend fun fetch(): List<Record1<A>>
    suspend fun fetchFirst(): Record1<A>?
    suspend fun count(): Long
    suspend fun page(page: Int, size: Int): Page<Record1<A>>
}

interface TypedProjection2<A, B> {
    suspend fun fetch(): List<Record2<A, B>>
    suspend fun fetchFirst(): Record2<A, B>?
    suspend fun count(): Long
    suspend fun page(page: Int, size: Int): Page<Record2<A, B>>
}

interface TypedProjection3<A, B, C> {
    suspend fun fetch(): List<Record3<A, B, C>>
    suspend fun fetchFirst(): Record3<A, B, C>?
    suspend fun count(): Long
    suspend fun page(page: Int, size: Int): Page<Record3<A, B, C>>
}

interface TypedProjection4<A, B, C, D> {
    suspend fun fetch(): List<Record4<A, B, C, D>>
    suspend fun fetchFirst(): Record4<A, B, C, D>?
    suspend fun count(): Long
    suspend fun page(page: Int, size: Int): Page<Record4<A, B, C, D>>
}

interface TypedProjection5<A, B, C, D, E> {
    suspend fun fetch(): List<Record5<A, B, C, D, E>>
    suspend fun fetchFirst(): Record5<A, B, C, D, E>?
    suspend fun count(): Long
    suspend fun page(page: Int, size: Int): Page<Record5<A, B, C, D, E>>
}

interface TypedProjection6<A, B, C, D, E, F> {
    suspend fun fetch(): List<Record6<A, B, C, D, E, F>>
    suspend fun fetchFirst(): Record6<A, B, C, D, E, F>?
    suspend fun count(): Long
    suspend fun page(page: Int, size: Int): Page<Record6<A, B, C, D, E, F>>
}

interface TypedProjection7<A, B, C, D, E, F, G> {
    suspend fun fetch(): List<Record7<A, B, C, D, E, F, G>>
    suspend fun fetchFirst(): Record7<A, B, C, D, E, F, G>?
    suspend fun count(): Long
    suspend fun page(page: Int, size: Int): Page<Record7<A, B, C, D, E, F, G>>
}

interface TypedProjection8<A, B, C, D, E, F, G, H> {
    suspend fun fetch(): List<Record8<A, B, C, D, E, F, G, H>>
    suspend fun fetchFirst(): Record8<A, B, C, D, E, F, G, H>?
    suspend fun count(): Long
    suspend fun page(page: Int, size: Int): Page<Record8<A, B, C, D, E, F, G, H>>
}
