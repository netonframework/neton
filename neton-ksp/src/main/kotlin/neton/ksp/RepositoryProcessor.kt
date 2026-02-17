package neton.ksp

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import java.io.OutputStreamWriter

private data class ColumnInfo(val propName: String, val columnName: String, val isId: Boolean)

private fun KSClassDeclaration.getDeclaredProperties(): Sequence<KSPropertyDeclaration> =
    declarations.filterIsInstance<KSPropertyDeclaration>()

private fun KSClassDeclaration.getDeclaredFunctions(): Sequence<KSFunctionDeclaration> =
    declarations.filterIsInstance<KSFunctionDeclaration>()

/**
 * Repository KSP Processor
 * 扫描 @Repository 接口，生成 Statements、RowMapper、ParamMapper、Store、RepositoryImpl
 */
class RepositoryProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String> = emptyMap()
) : SymbolProcessor {

    private val moduleId: String? = options["neton.moduleId"]?.takeIf { it.isNotBlank() }

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation("neton.database.annotations.Repository")
        val repositories = symbols.filterIsInstance<KSClassDeclaration>().toList()
        if (repositories.isEmpty()) return emptyList()

        for (repo in repositories) {
            try {
                processRepository(resolver, repo)
            } catch (e: Exception) {
                logger.error("Repository ${repo.simpleName}: ${e.message}")
            }
        }
        generateDatabaseRegistry(resolver, repositories)
        return emptyList()
    }

    private fun processRepository(resolver: Resolver, repo: KSClassDeclaration) {
        val entityType = resolveEntityType(repo) ?: run {
            logger.warn("Repository ${repo.simpleName.asString()}: could not resolve entity from Table<T>, skip")
            return
        }
        val entityName = entityType.simpleName.asString()
        val entityPkg = entityType.packageName.asString()
        val tableName = resolveTableName(entityType) ?: entityName.lowercase() + "s"
        val columns = resolveColumns(entityType)
        val idColumn = columns.find { it.isId }?.columnName ?: "id"
        val pkg = repo.packageName.asString()
        val repoName = repo.simpleName.asString()

        val insertCols = columns.filter { !it.isId }.joinToString(", ") { it.columnName }
        val insertVals = columns.filter { !it.isId }.joinToString(", ") { ":" + it.columnName }
        val updateSet = columns.filter { !it.isId }.joinToString(", ") { "${it.columnName} = :${it.columnName}" }

        generateStatements(
            pkg,
            entityName,
            entityPkg,
            tableName,
            columns,
            idColumn,
            insertCols,
            insertVals,
            updateSet,
            repo
        )
        generateRowMapper(pkg, entityName, entityPkg, columns)
        generateParamMapper(pkg, entityName, entityPkg, columns)
        generateTable(pkg, entityName, entityPkg, tableName, columns)
        generateRepositoryImpl(pkg, repoName, entityName, entityPkg, tableName, repo)
    }

    private fun generateDatabaseRegistry(resolver: Resolver, repositories: List<KSClassDeclaration>) {
        val pkg = repositories.firstOrNull()?.packageName?.asString() ?: return

        if (moduleId != null) {
            // 模块模式：写片段到 sink
            writeRepositorySink(pkg, repositories)
        } else {
            // 兼容模式：生成独立文件
            generateDatabaseRegistryFile(pkg, repositories)
        }
    }

    /** 模块模式：写 repository 绑定到 sink */
    private fun writeRepositorySink(pkg: String, repositories: List<KSClassDeclaration>) {
        ModuleFragmentSink.addStat(moduleId!!, "repositories", repositories.size)
        repositories.forEach { repo ->
            val fqn = repo.qualifiedName!!.asString()
            ModuleFragmentSink.addImport(moduleId!!, "import $fqn")
            ModuleFragmentSink.addImport(moduleId, "import $pkg.${repo.simpleName.asString()}Impl")
        }

        val bindings = repositories.joinToString("\n") { repo ->
            val repoName = repo.simpleName.asString()
            "        ctx.bind($repoName::class, ${repoName}Impl())"
        }
        ModuleFragmentSink.addFragment(moduleId!!, "repositories", "注册仓库", bindings)
    }

    /** 兼容模式：生成独立文件 */
    private fun generateDatabaseRegistryFile(pkg: String, repositories: List<KSClassDeclaration>) {
        val bindings = repositories.map { repo ->
            val repoName = repo.simpleName.asString()
            "ctx.bind($repoName::class, ${repoName}Impl())"
        }.joinToString("\n        ")
        val imports = repositories.map { "import ${it.qualifiedName!!.asString()}" }.joinToString("\n")
        val file = codeGenerator.createNewFile(Dependencies(true), pkg, "GeneratedDatabaseRegistry")
        OutputStreamWriter(file).use { w ->
            w.write(
                """
// AUTO-GENERATED - DO NOT EDIT
package $pkg

import neton.core.component.NetonContext
$imports

internal object GeneratedDatabaseRegistry {
    fun install(ctx: NetonContext) {
        $bindings
    }
}
""".trimIndent()
            )
        }
        val installFile = codeGenerator.createNewFile(Dependencies(true), pkg, "GeneratedDatabaseInstall")
        OutputStreamWriter(installFile).use { w ->
            w.write(
                """
// AUTO-GENERATED - DO NOT EDIT
package $pkg

import neton.core.component.NetonContext

fun installGeneratedRepositories(ctx: NetonContext) = GeneratedDatabaseRegistry.install(ctx)
""".trimIndent()
            )
        }
    }

    private fun resolveEntityType(repo: KSClassDeclaration): KSClassDeclaration? {
        for (superType in repo.superTypes) {
            val decl = superType.resolve().declaration
            val qn = decl.qualifiedName?.asString() ?: continue
            if (qn == "neton.database.api.Table" || qn.endsWith(".Table")) {
                val typeArgs = superType.resolve().arguments
                if (typeArgs.isNotEmpty()) {
                    val entityType = typeArgs.first().type?.resolve()?.declaration
                    if (entityType is KSClassDeclaration) return entityType
                }
            }
        }
        return null
    }

    private fun resolveTableName(entity: KSClassDeclaration): String? {
        for (ann in entity.annotations) {
            val qn = ann.annotationType.resolve().declaration.qualifiedName?.asString() ?: continue
            when {
                qn == "neton.database.annotations.Table" -> {
                    val v = ann.arguments.find { it.name?.asString() == "value" }?.value as? String
                    if (!v.isNullOrBlank()) return v
                }

                qn == "neton.database.annotations.Entity" -> {
                    val v = ann.arguments.find { it.name?.asString() == "tableName" }?.value as? String
                    if (!v.isNullOrBlank()) return v
                }
            }
        }
        return null
    }

    private fun resolveColumns(entity: KSClassDeclaration): List<ColumnInfo> {
        val columns = mutableListOf<ColumnInfo>()
        val params = entity.primaryConstructor?.parameters ?: return emptyList()
        for (param in params) {
            val propName = param.name?.asString() ?: continue
            var colName = propName.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()
            var isId = false
            for (ann in param.annotations) {
                val annDecl = ann.annotationType.resolve().declaration
                when ((annDecl as? KSClassDeclaration)?.qualifiedName?.asString()) {
                    "neton.database.annotations.Column" -> {
                        val arg = ann.arguments.find { it.name?.asString() == "name" }
                        val v = (arg?.value) as? String
                        if (!v.isNullOrBlank()) colName = v
                    }

                    "neton.database.annotations.Id" -> isId = true
                }
            }
            columns.add(ColumnInfo(propName, colName, isId))
        }
        return columns
    }

    private fun generateStatements(
        pkg: String,
        entityName: String,
        entityPkg: String,
        tableName: String,
        columns: List<ColumnInfo>,
        idColumn: String,
        insertCols: String,
        insertVals: String,
        updateSet: String,
        repo: KSClassDeclaration
    ) {
        val customMethods = mutableListOf<String>()
        for (func in repo.getDeclaredFunctions()) {
            val name = func.simpleName.asString()
            if ((name.startsWith("findBy") || name.startsWith("findAllBy")) && name != "findById") {
                val (sql, paramName) = methodToFindSql(name, tableName)
                if (sql != null) customMethods.add("val $name = Statement.create(\"$sql\")")
            } else if (name.startsWith("countBy")) {
                val (sql, _) = methodToCountSql(name, tableName)
                if (sql != null) customMethods.add("val $name = Statement.create(\"$sql\")")
            } else if (name.startsWith("deleteBy")) {
                val (sql, _) = methodToDeleteSql(name, tableName)
                if (sql != null) customMethods.add("val $name = Statement.create(\"$sql\")")
            }
        }
        val customBlock = if (customMethods.isNotEmpty()) "\n    " + customMethods.joinToString("\n    ") else ""

        val file = codeGenerator.createNewFile(Dependencies(true), pkg, "${entityName}Statements")
        OutputStreamWriter(file).use { w ->
            w.write(
                """
// AUTO-GENERATED - DO NOT EDIT
package $pkg

import io.github.smyrgeorge.sqlx4k.Statement
import neton.database.adapter.sqlx.EntityStatements

internal object ${entityName}Statements : EntityStatements {
    override val selectById = Statement.create("SELECT * FROM $tableName WHERE $idColumn = :id")
    override val selectAll = Statement.create("SELECT * FROM $tableName")
    override val countAll = Statement.create("SELECT COUNT(*) FROM $tableName")
    override val insert = Statement.create("INSERT INTO $tableName ($insertCols) VALUES ($insertVals)")
    override val update = Statement.create("UPDATE $tableName SET $updateSet WHERE $idColumn = :id")
    override val deleteById = Statement.create("DELETE FROM $tableName WHERE $idColumn = :id")$customBlock
}
""".trimIndent()
            )
        }
    }

    private fun methodToFindSql(name: String, table: String): Pair<String?, String?> {
        val rest = name.removePrefix("findAllBy").removePrefix("findBy")
        return when {
            rest.contains("Between") -> {
                val field = toSnake(rest.substringBefore("Between"))
                "SELECT * FROM $table WHERE $field BETWEEN :min AND :max" to field
            }

            rest.contains("And") -> {
                val parts = rest.split("And")
                val conds = parts.map { toSnake(it) + " = :" + toSnake(it) }
                "SELECT * FROM $table WHERE ${conds.joinToString(" AND ")}" to null
            }

            else -> "SELECT * FROM $table WHERE ${toSnake(rest)} = :${toSnake(rest)}" to toSnake(rest)
        }
    }

    private fun methodToCountSql(name: String, table: String): Pair<String?, String?> {
        val field = toSnake(name.removePrefix("countBy"))
        return "SELECT COUNT(*) FROM $table WHERE $field = :$field" to field
    }

    private fun methodToDeleteSql(name: String, table: String): Pair<String?, String?> {
        val field = toSnake(name.removePrefix("deleteBy"))
        return "DELETE FROM $table WHERE $field = :$field" to field
    }

    private fun toSnake(s: String) = s.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()

    private fun generateRowMapper(pkg: String, entityName: String, entityPkg: String, columns: List<ColumnInfo>) {
        val entityRef = if (pkg == entityPkg) entityName else "$entityPkg.$entityName"
        val mappings = columns.map { col ->
            val rhs = when {
                col.isId || col.propName == "id" || col.columnName == "id" -> "row.get(\"${col.columnName}\").toString().toLong()"
                col.columnName.endsWith("_at") || col.propName in listOf(
                    "createdAt",
                    "updatedAt"
                ) -> "row.get(\"${col.columnName}\").toString().toLong()"

                col.columnName in listOf("age", "status") || col.propName in listOf(
                    "age",
                    "status"
                ) -> "row.get(\"${col.columnName}\").toString().toInt()"

                else -> "row.get(\"${col.columnName}\").toString()"
            }
            "${col.propName} = $rhs"
        }.joinToString(",\n        ")

        val file = codeGenerator.createNewFile(Dependencies(true), pkg, "${entityName}RowMapper")
        OutputStreamWriter(file).use { w ->
            w.write(
                """
// AUTO-GENERATED - DO NOT EDIT
package $pkg

import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.RowMapper
import io.github.smyrgeorge.sqlx4k.ValueEncoderRegistry

internal object ${entityName}RowMapper : RowMapper<$entityRef> {
    override fun map(row: ResultSet.Row, converters: ValueEncoderRegistry): $entityRef = $entityRef(
        $mappings
    )
}
""".trimIndent()
            )
        }
    }

    private fun generateParamMapper(pkg: String, entityName: String, entityPkg: String, columns: List<ColumnInfo>) {
        val entityRef = if (pkg == entityPkg) entityName else "$entityPkg.$entityName"
        val mappings = columns.map { "\"${it.columnName}\" to it.${it.propName}" }.joinToString(",\n            ")
        val file = codeGenerator.createNewFile(Dependencies(true), pkg, "${entityName}ParamMapper")
        OutputStreamWriter(file).use { w ->
            w.write(
                """
// AUTO-GENERATED - DO NOT EDIT
package $pkg

internal object ${entityName}ParamMapper {
    fun toMap(it: $entityRef) = mapOf(
            $mappings
    )
}
""".trimIndent()
            )
        }
    }

    private fun columnToSqliteType(prop: String, isId: Boolean): String = when {
        isId -> "INTEGER PRIMARY KEY AUTOINCREMENT"
        prop in listOf("createdAt", "updatedAt") || prop.endsWith("At") -> "INTEGER"
        prop in listOf("age", "status") || prop.endsWith("Count") -> "INTEGER"
        else -> "TEXT"
    }

    private fun generateDdl(tableName: String, columns: List<ColumnInfo>): String {
        val defs = columns.map { c ->
            val sqlType = columnToSqliteType(c.propName, c.isId)
            if (c.isId) "${c.columnName} $sqlType" else "${c.columnName} $sqlType"
        }
        return "CREATE TABLE IF NOT EXISTS $tableName (${defs.joinToString(", ")})"
    }

    private fun generateTable(
        pkg: String,
        entityName: String,
        entityPkg: String,
        tableName: String,
        columns: List<ColumnInfo>
    ) {
        val entityRef = if (pkg == entityPkg) entityName else "$entityPkg.$entityName"
        val ddl = generateDdl(tableName, columns)
        val file = codeGenerator.createNewFile(Dependencies(true), pkg, "${entityName}Table")
        OutputStreamWriter(file).use { w ->
            w.write(
                """
// AUTO-GENERATED - DO NOT EDIT
package $pkg

import neton.database.api.Table
import neton.database.adapter.sqlx.SqlxDatabase
import neton.database.adapter.sqlx.SqlxStore

internal object ${entityName}Table : SqlxStore<$entityRef>(
    db = SqlxDatabase.require(),
    statements = ${entityName}Statements,
    mapper = ${entityName}RowMapper,
    toParams = ${entityName}ParamMapper::toMap,
    getId = { it.id }
) {
    suspend fun ensureTable() {
        SqlxDatabase.executeDdl("$ddl")
    }
}
""".trimIndent()
            )
        }
    }

    /** 从方法名推导 SQL 占位符名列表 */
    private fun methodToParamNames(name: String): List<String> = when {
        (name.startsWith("findBy") || name.startsWith("findAllBy")) && name != "findById" -> {
            val rest = name.removePrefix("findAllBy").removePrefix("findBy")
            when {
                rest.contains("Between") -> listOf("min", "max")
                rest.contains("And") -> rest.split("And").map { toSnake(it) }
                else -> listOf(toSnake(rest))
            }
        }

        name.startsWith("countBy") -> listOf(toSnake(name.removePrefix("countBy")))
        name.startsWith("deleteBy") -> listOf(toSnake(name.removePrefix("deleteBy")))
        else -> emptyList()
    }

    private fun returnsList(func: KSFunctionDeclaration): Boolean {
        val str = func.returnType?.resolve()?.toString() ?: return false
        return str.startsWith("List<") || str == "List"
    }

    private fun generateRepositoryImpl(
        pkg: String,
        repoName: String,
        entityName: String,
        entityPkg: String,
        tableName: String,
        repo: KSClassDeclaration
    ) {
        val entityRef = if (pkg == entityPkg) entityName else "$entityPkg.$entityName"
        val customImpls = mutableListOf<String>()
        for (func in repo.getDeclaredFunctions()) {
            val name = func.simpleName.asString()
            if (name in listOf("equals", "hashCode", "toString")) continue
            val params = func.parameters.filter { it.name != null }
            val paramNames = methodToParamNames(name)
            when {
                name == "ensureTable" -> {
                    customImpls.add("override suspend fun ensureTable() { ${entityName}Table.ensureTable() }")
                }

                (name.startsWith("findBy") || name.startsWith("findAllBy")) && name != "findById" -> {
                    val mapEntries = paramNames.zip(params)
                        .joinToString(", ") { pair -> "\"${pair.first}\" to ${pair.second.name?.asString()}" }
                    val mapArg = "mapOf($mapEntries)"
                    val call = if (returnsList(func)) "queryList" else "queryOne"
                    val paramSig = params.joinToString(", ") { p -> "${p.name?.asString()}: ${p.type}" }
                    customImpls.add("override suspend fun $name($paramSig) = ${entityName}Table.$call(${entityName}Statements.$name, $mapArg)")
                }

                name.startsWith("countBy") -> {
                    val key = paramNames.firstOrNull() ?: continue
                    val pName = params.firstOrNull()?.name?.asString() ?: key
                    val paramSig = params.joinToString(", ") { p -> "${p.name?.asString()}: ${p.type}" }
                    customImpls.add("override suspend fun $name($paramSig) = ${entityName}Table.queryScalar(${entityName}Statements.$name, mapOf(\"$key\" to $pName))")
                }

                name.startsWith("deleteBy") -> {
                    val key = paramNames.firstOrNull() ?: continue
                    val pName = params.firstOrNull()?.name?.asString() ?: key
                    val paramSig = params.joinToString(", ") { p -> "${p.name?.asString()}: ${p.type}" }
                    customImpls.add("override suspend fun $name($paramSig) { ${entityName}Table.execute(${entityName}Statements.$name, mapOf(\"$key\" to $pName)) }")
                }
            }
        }
        val customBlock = if (customImpls.isNotEmpty()) "\n    " + customImpls.joinToString("\n    ") else ""

        val file = codeGenerator.createNewFile(Dependencies(true), pkg, "${repoName}Impl")
        OutputStreamWriter(file).use { w ->
            w.write(
                """
// AUTO-GENERATED - DO NOT EDIT
package $pkg

import neton.database.api.Table

internal class ${repoName}Impl : $repoName {
    private val table = ${entityName}Table
    private fun idToLong(id: kotlin.Any): Long = when (id) {
        is Long -> id
        is Number -> id.toLong()
        else -> throw IllegalArgumentException("id must be Long or Number")
    }
    override suspend fun findById(id: kotlin.Any) = table.get(idToLong(id))
    override suspend fun findAll() = table.findAll()
    override suspend fun save(entity: $entityRef) = table.save(entity)
    override suspend fun saveAll(entities: List<$entityRef>) = table.saveAll(entities)
    override suspend fun insert(entity: $entityRef) = table.insert(entity)
    override suspend fun insertBatch(entities: List<$entityRef>) = table.insertBatch(entities)
    override suspend fun update(entity: $entityRef) = table.update(entity)
    override suspend fun updateBatch(entities: List<$entityRef>) = table.updateBatch(entities)
    override suspend fun deleteById(id: kotlin.Any) = table.destroy(idToLong(id))
    override suspend fun delete(entity: $entityRef) = table.delete(entity)
    override suspend fun count() = table.count()
    override suspend fun exists(id: kotlin.Any) = table.exists(idToLong(id))
    override fun query() = table.query()
    override suspend fun <R> transaction(block: suspend Table<$entityRef>.() -> R) = table.transaction(block)$customBlock
}
""".trimIndent()
            )
        }
    }
}

class RepositoryProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return RepositoryProcessor(environment.codeGenerator, environment.logger, environment.options)
    }
}
