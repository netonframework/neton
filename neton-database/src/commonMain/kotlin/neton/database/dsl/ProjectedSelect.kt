package neton.database.dsl

import neton.database.api.DbContext
import neton.database.api.Page
import neton.database.api.Row
import neton.database.adapter.sqlx.selectRows
import neton.database.adapter.sqlx.countRows

/**
 * ProjectedSelect（Row 逃生口，绑定 DbContext）。
 * 用于 JOIN 场景，返回 Row 列表，适合 into / intoOrNull / groupOneToMany 手动映射。
 */
class ProjectedSelect internal constructor(
    private val db: DbContext,
    private val ast: SelectAst
) {
    /** Row 逃生口：适合 intoOrNull / into / groupOneToMany 手动映射 */
    suspend fun fetchRows(): List<Row> = db.selectRows(ast)
    suspend fun count(): Long = db.countRows(ast)
    suspend fun pageRows(page: Int, size: Int): Page<Row> {
        val total = count()
        val items = db.selectRows(ast.copy(limit = size, offset = (page - 1) * size))
        return Page(items, total, page, size)
    }
}
