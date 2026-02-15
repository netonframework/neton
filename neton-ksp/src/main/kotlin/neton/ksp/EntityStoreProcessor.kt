package neton.ksp

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import java.io.OutputStreamWriter

private data class EntityColumnInfo(val propName: String, val columnName: String, val isId: Boolean, val propType: String)

/**
 * v3 Table 生成器：只生成 metadata + Table，不生成 Repository/Impl
 * 处理 @Table 实体 → UserMeta, UserRowMapper, UserTable (Table by SqlxTableAdapter)
 */
class EntityStoreProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation("neton.database.annotations.Table")
        val entities = symbols.filterIsInstance<KSClassDeclaration>().toList()
        if (entities.isEmpty()) return emptyList()

        for (entity in entities) {
            try {
                processEntity(resolver, entity)
            } catch (e: Exception) {
                logger.error("Entity ${entity.simpleName}: ${e.message}")
            }
        }
        return emptyList()
    }

    private fun processEntity(resolver: Resolver, entity: KSClassDeclaration) {
        val entityName = entity.simpleName.asString()
        val entityPkg = entity.packageName.asString()
        val tableName = resolveTableName(entity) ?: entityName.lowercase() + "s"
        val columns = resolveColumns(entity)
        val idColumn = columns.find { it.isId }?.columnName ?: "id"
        val pkg = entityPkg

        val idCol = columns.find { it.isId || it.propName == "id" }
        val idType = idCol?.propType?.removeSuffix("?") ?: "Long"
        generateMeta(pkg, entityName, entityPkg, tableName, columns, idColumn)
        generateRowMapper(pkg, entityName, entityPkg, columns)
        generateTable(pkg, entityName, entityPkg, tableName, columns, idColumn, idType)
        generateExtensions(pkg, entityName, entityPkg, columns, idType)
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

    private fun resolveColumns(entity: KSClassDeclaration): List<EntityColumnInfo> {
        val list = mutableListOf<EntityColumnInfo>()
        val params = entity.primaryConstructor?.parameters ?: return list
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
            val resolved = param.type.resolve()
            val qn = resolved.declaration.qualifiedName?.asString() ?: "Any"
            val propType = qn.substringAfterLast('.') + if (resolved.isMarkedNullable) "?" else ""
            list.add(EntityColumnInfo(propName, colName, isId, propType))
        }
        return list
    }

    private fun generateMeta(pkg: String, entityName: String, entityPkg: String, tableName: String, columns: List<EntityColumnInfo>, idColumn: String) {
        val entityRef = if (pkg == entityPkg) entityName else "$entityPkg.$entityName"
        val colsList = columns.joinToString(", ") { "\"${it.columnName}\"" }
        val file = codeGenerator.createNewFile(Dependencies(true), pkg, "${entityName}Meta")
        OutputStreamWriter(file).use { w ->
            w.write("""
// AUTO-GENERATED - DO NOT EDIT
package $pkg

import neton.database.api.EntityMeta

internal object ${entityName}Meta : EntityMeta<$entityRef> {
    override val table = "$tableName"
    override val idColumn = "$idColumn"
    override val columns = listOf($colsList)
}
""".trimIndent())
        }
    }

    private fun generateRowMapper(pkg: String, entityName: String, entityPkg: String, columns: List<EntityColumnInfo>) {
        val entityRef = if (pkg == entityPkg) entityName else "$entityPkg.$entityName"
        val mappings = columns.map { col ->
            val rhs = when {
                col.isId || col.propName == "id" -> "row.get(\"${col.columnName}\")?.toString()?.toLong()"
                col.columnName.endsWith("_id") || col.propName.endsWith("Id") -> "row.get(\"${col.columnName}\").toString().toLong()"
                col.columnName in listOf("age", "status") || col.propName in listOf("age", "status") -> "row.get(\"${col.columnName}\").toString().toInt()"
                else -> "row.get(\"${col.columnName}\").toString()"
            }
            "${col.propName} = $rhs"
        }.joinToString(",\n        ")
        val file = codeGenerator.createNewFile(Dependencies(true), pkg, "${entityName}RowMapper")
        OutputStreamWriter(file).use { w ->
            w.write("""
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
""".trimIndent())
        }
    }

    private fun generateTable(pkg: String, entityName: String, entityPkg: String, tableName: String, columns: List<EntityColumnInfo>, idColumn: String, idType: String) {
        val entityRef = if (pkg == entityPkg) entityName else "$entityPkg.$entityName"
        val insertCols = columns.filter { !it.isId }.joinToString(", ") { it.columnName }
        val insertVals = columns.filter { !it.isId }.joinToString(", ") { ":" + it.columnName }
        val updateSet = columns.filter { !it.isId }.joinToString(", ") { "${it.columnName} = :${it.columnName}" }
        val toParamsEntries = columns.map { "\"${it.columnName}\" to it.${it.propName}" }.joinToString(",\n            ")
        val file = codeGenerator.createNewFile(Dependencies(true), pkg, "${entityName}Table")
        OutputStreamWriter(file).use { w ->
            w.write("""
// AUTO-GENERATED - DO NOT EDIT
// 对外只暴露 Table<User, ID> 接口，不暴露底层实现，便于未来换引擎
package $pkg

import neton.database.api.Table
import neton.database.adapter.sqlx.SqlxTableAdapter
import neton.database.adapter.sqlx.SqlxDatabase

object ${entityName}Table : Table<$entityRef, $idType> by SqlxTableAdapter<$entityRef, $idType>(
    meta = ${entityName}Meta,
    dbProvider = { SqlxDatabase.require() },
    mapper = ${entityName}RowMapper,
    toParams = { it -> mapOf(
            $toParamsEntries
    )},
    getId = { it.id }
)
""".trimIndent())
        }
    }

    private fun generateExtensions(pkg: String, entityName: String, entityPkg: String, columns: List<EntityColumnInfo>, idType: String) {
        val entityRef = if (pkg == entityPkg) entityName else "$entityPkg.$entityName"
        val nonId = columns.filter { !it.isId && it.propName != "id" }
        val scopeVars = nonId.joinToString("\n    ") { "var ${it.propName}: ${it.propType}" }
        val scopeInit = nonId.joinToString("\n        ") { "${it.propName} = initial.${it.propName}" }
        val copyArgs = nonId.joinToString(", ") { "${it.propName} = scope.${it.propName}" }
        val file = codeGenerator.createNewFile(Dependencies(true), pkg, "${entityName}Extensions")
        OutputStreamWriter(file).use { w ->
            w.write("""
// AUTO-GENERATED - DO NOT EDIT
// 定型 API：UserTable.get(id) / UserTable.destroy(id) / UserTable.update(id){ } / UserTable.query{ } / user.save() / user.delete()
// update(id){ } 为 mutate 风格：lambda 内直接赋值，copy 由框架内部生成
package $pkg

import neton.database.api.Predicate
import neton.database.api.PredicateScope
import neton.database.api.Query

/** 用于 UserTable.update(id) { name = x; email = y } 的可变作用域，copy 在内部生成 */
class ${entityName}UpdateScope(initial: $entityRef) {
    $scopeVars
    init {
        $scopeInit
    }
}

// ---------- 表级 UserTable.update(id){ }（mutate 风格）----------
suspend fun ${entityName}Table.update(id: $idType, block: ${entityName}UpdateScope.() -> kotlin.Unit): $entityRef? {
    val current = ${entityName}Table.get(id) ?: return null
    val scope = ${entityName}UpdateScope(current)
    scope.block()
    val updated = current.copy($copyArgs)
    ${entityName}Table.update(updated)
    return updated
}

// ---------- 实例级（user.xxx）----------
suspend fun $entityRef.save(): $entityRef = ${entityName}Table.save(this)
suspend fun $entityRef.delete(): Boolean = ${entityName}Table.delete(this)
""".trimIndent())
        }
    }
}

class EntityStoreProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return EntityStoreProcessor(environment.codeGenerator, environment.logger)
    }
}
