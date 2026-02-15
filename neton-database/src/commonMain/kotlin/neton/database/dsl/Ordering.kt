package neton.database.dsl

data class Ordering(val column: ColumnRef, val dir: Dir)

enum class Dir { ASC, DESC }

fun ColumnRef.asc(): Ordering = Ordering(this, Dir.ASC)
fun ColumnRef.desc(): Ordering = Ordering(this, Dir.DESC)
