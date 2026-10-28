@file:OptIn(neton.core.annotations.InternalNetonApi::class)

import kotlinx.coroutines.runBlocking
import neton.core.Neton
import neton.core.config.ConfigLoader
import neton.database.adapter.sqlx.SqlxDatabase
import neton.database.config.DatabaseConfig
import neton.database.database
import neton.database.migration.MigrationCommand
import neton.database.migration.MigrationConfig
import neton.database.migration.MigrationEngine
import neton.database.migration.MigrationResult
import neton.core.module.MigrationDialect
import neton.http.http
import neton.module.reference.generated.ReferenceModuleManifest
import neton.routing.routing

fun main(args: Array<String>) {
    if (args.firstOrNull() == "migrate") {
        require(args.size == 2) { "Usage: migrate up|status|verify" }
        val command = MigrationCommand.valueOf(args[1].uppercase())
        val config = requireNotNull(ConfigLoader.loadModuleConfig("database", configPath = "config"))
        @Suppress("UNCHECKED_CAST")
        val connection = DatabaseConfig.fromMap(config["default"] as Map<String, Any>)
        val history = (config["migration"] as Map<*, *>)["history_table"] as String
        runBlocking {
            val db = SqlxDatabase.initialize(connection)
            try {
                val result = MigrationEngine(db, MigrationConfig(MigrationDialect.POSTGRESQL, history))
                    .run(command, ReferenceModuleManifest.migrations())
                check(result.warnings.isEmpty()) { "Migration warnings: ${result.warnings}" }
                check(when (result) {
                    is MigrationResult.Up -> result.ok
                    is MigrationResult.Status -> result.historyExists && result.scripts.isNotEmpty() &&
                        result.scripts.all { it.state == MigrationResult.ScriptState.EXECUTED }
                    is MigrationResult.Verify -> result.ok && result.verifiedCount > 0
                    is MigrationResult.Aborted -> false
                }) { "Migration gate failed: $result" }
                println("NETON_REFERENCE_MIGRATION_${command.name}_OK")
            } finally {
                SqlxDatabase.close()
            }
        }
        return
    }
    require(args.isEmpty()) { "Usage: [migrate up|status|verify]" }
    Neton.run(args) {
        database { }
        http { }
        routing { }
        modules(ReferenceModuleManifest)
    }
}
