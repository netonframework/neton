package compatibility
fun databaseSupport() = neton.database.adapter.sqlx.SqlxDatabase.currentDriver()
