package neton.database

import kotlin.reflect.KProperty1
import neton.database.api.EntityQuery
import neton.database.api.Order
import neton.database.api.Page
import neton.database.api.ProjectionQuery
import neton.database.api.Query
import neton.database.api.QueryBuilder
import neton.database.api.Row
import neton.database.api.Table
import neton.database.api.UpdateScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Table v2 契约测试（语义升级锁死）
 *
 * @see Neton-Database-API-Freeze-v2.md 第十节
 *
 * - Test 1：Table 接口必须提供 get/destroy/query 等单表 CRUD 契约（编译期验证：此 stub 必须实现全部方法）
 * - Test 2：Store 不实现 Table，由约束 A 固化；tableRegistry 仅接受 Table 类型
 */
class TableUserContractTest {

    private data class ContractTestEntity(val id: Long?)

    private val queryStub = object : Query<ContractTestEntity> {
        override fun orderBy(prop: KProperty1<ContractTestEntity, *>, ascending: Boolean) = this
        override fun orderBy(order: Pair<KProperty1<ContractTestEntity, *>, Boolean>) = this
        override fun orderBy(vararg orders: Order<ContractTestEntity>) = this
        override fun limit(n: Int) = this
        override fun offset(n: Int) = this
        override fun page(page: Int, size: Int) = this
        override suspend fun list() = emptyList<ContractTestEntity>()
        override suspend fun first() = null
        override suspend fun firstOrNull() = null
        override suspend fun one(): ContractTestEntity = error("stub")
        override suspend fun oneOrNull() = null
        override suspend fun count() = 0L
        override suspend fun exists() = false
        override fun flow(): Flow<ContractTestEntity> = flow { }
        override suspend fun delete() = 0L
        override suspend fun update(block: UpdateScope<ContractTestEntity>.() -> Unit) = 0L
        override suspend fun listPage() = Page.of<ContractTestEntity>(emptyList(), 0L, 1, 20)
    }

    /**
     * 编译期契约：Table 必须包含 get/destroy/query。
     * 若 Table 接口移除任何方法，此 stub 编译失败。
     */
    private val tableStub: Table<ContractTestEntity, Long> = object : Table<ContractTestEntity, Long> {
        override suspend fun get(id: Long): ContractTestEntity? = null
        override suspend fun findAll(): List<ContractTestEntity> = emptyList()
        override suspend fun save(entity: ContractTestEntity): ContractTestEntity = entity
        override suspend fun saveAll(entities: List<ContractTestEntity>): List<ContractTestEntity> = entities
        override suspend fun insert(entity: ContractTestEntity): ContractTestEntity = entity
        override suspend fun insertBatch(entities: List<ContractTestEntity>): Int = 0
        override suspend fun update(entity: ContractTestEntity): Boolean = false
        override suspend fun updateBatch(entities: List<ContractTestEntity>): Int = 0
        override suspend fun destroy(id: Long): Boolean = false
        override suspend fun destroyMany(ids: Collection<Long>): Int = 0
        override suspend fun many(ids: Collection<Long>): List<ContractTestEntity> = emptyList()
        override suspend fun oneWhere(block: neton.database.dsl.PredicateScope.() -> neton.database.dsl.Predicate): ContractTestEntity? = null
        override suspend fun existsWhere(block: neton.database.dsl.PredicateScope.() -> neton.database.dsl.Predicate): Boolean = false
        override suspend fun delete(entity: ContractTestEntity): Boolean = false
        override suspend fun count(): Long = 0L
        override suspend fun exists(id: Long): Boolean = false
        override fun query(): QueryBuilder<ContractTestEntity> = error("stub")
        override fun query(block: neton.database.dsl.QueryScope<ContractTestEntity>.() -> Unit): EntityQuery<ContractTestEntity> =
            object : EntityQuery<ContractTestEntity> {
                override suspend fun list() = emptyList<ContractTestEntity>()
                override suspend fun count() = 0L
                override suspend fun page(page: Int, size: Int) = Page.of(emptyList<ContractTestEntity>(), 0L, page, size)
                override fun select(vararg columnNames: String): ProjectionQuery = object : ProjectionQuery {
                    override suspend fun rows() = emptyList<Row>()
                    override suspend fun count() = 0L
                    override suspend fun page(page: Int, size: Int) = Page.of(emptyList<Row>(), 0L, page, size)
                }
            }
        override suspend fun <R> withTransaction(block: suspend Table<ContractTestEntity, Long>.() -> R): R = error("stub")
    }

    @Test
    fun tableInterface_mustHaveGetAndDestroy() {
        assertNotNull(tableStub)
        kotlinx.coroutines.runBlocking {
            tableStub.get(1L)
            tableStub.destroy(1L)
        }
    }

    @Test
    fun tableInterface_mustHaveQuery() = kotlinx.coroutines.runBlocking {
        assertNotNull(tableStub)
        // query { where { all() } } 返回 EntityQuery，验证 list/count
        val q = tableStub.query { where { all() } }
        assertNotNull(q)
        assertEquals(0, q.count())
    }
}
