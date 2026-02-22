package neton.database.dsl

import neton.database.api.DbContext
import neton.database.api.Page
import neton.database.api.Row
import neton.database.adapter.sqlx.selectRows
import neton.database.adapter.sqlx.countRows

/**
 * TypedProjectedSelect（Phase 4 JOIN 强类型投影，路径 B）。
 * 编译期捕获 ColRef 的类型读取器，不依赖运行时反射。
 */

class TypedProjectedSelect1<A> internal constructor(
    private val db: DbContext,
    private val ast: SelectAst,
    private val read1: (Row) -> A
) {
    suspend fun fetch(): List<Record1<A>> =
        db.selectRows(ast).map { Rec1(read1(it)) }
    suspend fun count(): Long = db.countRows(ast)
    suspend fun page(page: Int, size: Int): Page<Record1<A>> {
        val total = count()
        val items = db.selectRows(ast.copy(limit = size, offset = (page - 1) * size))
                      .map { Rec1(read1(it)) }
        return Page(items, total, page, size)
    }
}

class TypedProjectedSelect2<A, B> internal constructor(
    private val db: DbContext,
    private val ast: SelectAst,
    private val read1: (Row) -> A,
    private val read2: (Row) -> B
) {
    suspend fun fetch(): List<Record2<A, B>> =
        db.selectRows(ast).map { Rec2(read1(it), read2(it)) }
    suspend fun count(): Long = db.countRows(ast)
    suspend fun page(page: Int, size: Int): Page<Record2<A, B>> {
        val total = count()
        val items = db.selectRows(ast.copy(limit = size, offset = (page - 1) * size))
                      .map { Rec2(read1(it), read2(it)) }
        return Page(items, total, page, size)
    }
}

class TypedProjectedSelect3<A, B, C> internal constructor(
    private val db: DbContext,
    private val ast: SelectAst,
    private val read1: (Row) -> A,
    private val read2: (Row) -> B,
    private val read3: (Row) -> C
) {
    suspend fun fetch(): List<Record3<A, B, C>> =
        db.selectRows(ast).map { Rec3(read1(it), read2(it), read3(it)) }
    suspend fun count(): Long = db.countRows(ast)
    suspend fun page(page: Int, size: Int): Page<Record3<A, B, C>> {
        val total = count()
        val items = db.selectRows(ast.copy(limit = size, offset = (page - 1) * size))
                      .map { Rec3(read1(it), read2(it), read3(it)) }
        return Page(items, total, page, size)
    }
}

class TypedProjectedSelect4<A, B, C, D> internal constructor(
    private val db: DbContext,
    private val ast: SelectAst,
    private val read1: (Row) -> A,
    private val read2: (Row) -> B,
    private val read3: (Row) -> C,
    private val read4: (Row) -> D
) {
    suspend fun fetch(): List<Record4<A, B, C, D>> =
        db.selectRows(ast).map { Rec4(read1(it), read2(it), read3(it), read4(it)) }
    suspend fun count(): Long = db.countRows(ast)
    suspend fun page(page: Int, size: Int): Page<Record4<A, B, C, D>> {
        val total = count()
        val items = db.selectRows(ast.copy(limit = size, offset = (page - 1) * size))
                      .map { Rec4(read1(it), read2(it), read3(it), read4(it)) }
        return Page(items, total, page, size)
    }
}

class TypedProjectedSelect5<A, B, C, D, E> internal constructor(
    private val db: DbContext,
    private val ast: SelectAst,
    private val read1: (Row) -> A,
    private val read2: (Row) -> B,
    private val read3: (Row) -> C,
    private val read4: (Row) -> D,
    private val read5: (Row) -> E
) {
    suspend fun fetch(): List<Record5<A, B, C, D, E>> =
        db.selectRows(ast).map { Rec5(read1(it), read2(it), read3(it), read4(it), read5(it)) }
    suspend fun count(): Long = db.countRows(ast)
    suspend fun page(page: Int, size: Int): Page<Record5<A, B, C, D, E>> {
        val total = count()
        val items = db.selectRows(ast.copy(limit = size, offset = (page - 1) * size))
                      .map { Rec5(read1(it), read2(it), read3(it), read4(it), read5(it)) }
        return Page(items, total, page, size)
    }
}

class TypedProjectedSelect6<A, B, C, D, E, F> internal constructor(
    private val db: DbContext,
    private val ast: SelectAst,
    private val read1: (Row) -> A,
    private val read2: (Row) -> B,
    private val read3: (Row) -> C,
    private val read4: (Row) -> D,
    private val read5: (Row) -> E,
    private val read6: (Row) -> F
) {
    suspend fun fetch(): List<Record6<A, B, C, D, E, F>> =
        db.selectRows(ast).map { Rec6(read1(it), read2(it), read3(it), read4(it), read5(it), read6(it)) }
    suspend fun count(): Long = db.countRows(ast)
    suspend fun page(page: Int, size: Int): Page<Record6<A, B, C, D, E, F>> {
        val total = count()
        val items = db.selectRows(ast.copy(limit = size, offset = (page - 1) * size))
                      .map { Rec6(read1(it), read2(it), read3(it), read4(it), read5(it), read6(it)) }
        return Page(items, total, page, size)
    }
}

class TypedProjectedSelect7<A, B, C, D, E, F, G> internal constructor(
    private val db: DbContext,
    private val ast: SelectAst,
    private val read1: (Row) -> A,
    private val read2: (Row) -> B,
    private val read3: (Row) -> C,
    private val read4: (Row) -> D,
    private val read5: (Row) -> E,
    private val read6: (Row) -> F,
    private val read7: (Row) -> G
) {
    suspend fun fetch(): List<Record7<A, B, C, D, E, F, G>> =
        db.selectRows(ast).map { Rec7(read1(it), read2(it), read3(it), read4(it), read5(it), read6(it), read7(it)) }
    suspend fun count(): Long = db.countRows(ast)
    suspend fun page(page: Int, size: Int): Page<Record7<A, B, C, D, E, F, G>> {
        val total = count()
        val items = db.selectRows(ast.copy(limit = size, offset = (page - 1) * size))
                      .map { Rec7(read1(it), read2(it), read3(it), read4(it), read5(it), read6(it), read7(it)) }
        return Page(items, total, page, size)
    }
}

class TypedProjectedSelect8<A, B, C, D, E, F, G, H> internal constructor(
    private val db: DbContext,
    private val ast: SelectAst,
    private val read1: (Row) -> A,
    private val read2: (Row) -> B,
    private val read3: (Row) -> C,
    private val read4: (Row) -> D,
    private val read5: (Row) -> E,
    private val read6: (Row) -> F,
    private val read7: (Row) -> G,
    private val read8: (Row) -> H
) {
    suspend fun fetch(): List<Record8<A, B, C, D, E, F, G, H>> =
        db.selectRows(ast).map { Rec8(read1(it), read2(it), read3(it), read4(it), read5(it), read6(it), read7(it), read8(it)) }
    suspend fun count(): Long = db.countRows(ast)
    suspend fun page(page: Int, size: Int): Page<Record8<A, B, C, D, E, F, G, H>> {
        val total = count()
        val items = db.selectRows(ast.copy(limit = size, offset = (page - 1) * size))
                      .map { Rec8(read1(it), read2(it), read3(it), read4(it), read5(it), read6(it), read7(it), read8(it)) }
        return Page(items, total, page, size)
    }
}
