package neton.ksp

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import java.io.OutputStreamWriter

private data class EntityColumnInfo(
    val propName: String,
    val columnName: String,
    val isId: Boolean,
    val propType: String,
    val isSoftDelete: Boolean = false,
    val isCreatedAt: Boolean = false,
    val isUpdatedAt: Boolean = false,
    /** 仅对 isId=true 字段有意义；与 @Id(autoGenerate=...) 一致；默认 true。 */
    val idAutoGenerate: Boolean = true,
)

/**
 * Table 生成器：处理 @Table 实体，生成 metadata + Table 实现
 * @Table data class → EntityMeta, RowMapper, Table (Table by SqlxTableAdapter), Extensions
 */
class EntityTableProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String> = emptyMap()
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

        // 检测用户是否已手写 XxxTable（Facade 模式）
        // 若用户已定义 XxxTable，则只生成 XxxTableImpl（internal），避免冲突
        // 约定：Facade 可以在 entity 同包，也可以在 "table" 包
        val facadeName = "${entityName}Table"
        val candidateFqns = listOf("$pkg.$facadeName", "table.$facadeName")
        val userDefinedTable = candidateFqns.firstNotNullOfOrNull { fqn ->
            resolver.getClassDeclarationByName(resolver.getKSNameFromString(fqn))?.takeIf {
                it.origin != Origin.SYNTHETIC &&
                it.origin != Origin.KOTLIN_LIB &&
                it.origin != Origin.JAVA_LIB
            }
        }
        val hasFacade = userDefinedTable != null
        // Facade 的全限定包名（用于 import）
        val facadePkg = userDefinedTable?.packageName?.asString()

        if (hasFacade && userDefinedTable != null) {
            // Facade 必须是 object，否则编译期直接报错
            if (userDefinedTable.classKind != ClassKind.OBJECT) {
                logger.error(
                    "Facade $facadeName must be declared as 'object', " +
                    "but found '${userDefinedTable.classKind}'. " +
                    "Expected: object $facadeName : Table<$entityName, $idType> by ${entityName}TableImpl"
                )
                return
            }
            logger.info("Facade detected: $facadeName is user-defined (package: $facadePkg), generating ${entityName}TableImpl instead")
        }

        generateMeta(pkg, entityName, entityPkg, tableName, columns, idColumn)
        generateRowMapper(pkg, entityName, entityPkg, columns)
        generateTable(pkg, entityName, entityPkg, tableName, columns, idColumn, idType, hasFacade)
        generateExtensions(pkg, entityName, entityPkg, columns, idType, hasFacade, facadePkg)
        generateTableDef(pkg, entityName, entityPkg, tableName, columns)  // Phase 2
        generateEntityMapper(pkg, entityName, entityPkg, columns)  // Phase 3
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
        // PROPERTY-targeted annotations live on the property, not the value parameter.
        // Build a lookup so we can merge both sources.
        val propertyAnnotations = entity.getAllProperties().associate { prop ->
            prop.simpleName.asString() to prop.annotations.toList()
        }
        for (param in params) {
            val propName = param.name?.asString() ?: continue
            var colName = propName.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()
            var isId = false
            var isSoftDelete = false
            var isCreatedAt = false
            var isUpdatedAt = false
            var idAutoGenerate = true
            val allAnnotations = param.annotations.toList() + (propertyAnnotations[propName] ?: emptyList())
            for (ann in allAnnotations) {
                val annDecl = ann.annotationType.resolve().declaration
                val annQn = (annDecl as? KSClassDeclaration)?.qualifiedName?.asString()
                when (annQn) {
                    "neton.database.annotations.Column" -> {
                        val arg = ann.arguments.find { it.name?.asString() == "name" }
                        val v = (arg?.value) as? String
                        if (!v.isNullOrBlank()) colName = v
                    }

                    "neton.database.annotations.Id" -> {
                        isId = true
                        val arg = ann.arguments.find { it.name?.asString() == "autoGenerate" }?.value as? Boolean
                        if (arg != null) idAutoGenerate = arg
                    }
                    "neton.database.annotations.SoftDelete" -> isSoftDelete = true
                    "neton.database.annotations.CreatedAt" -> isCreatedAt = true
                    "neton.database.annotations.UpdatedAt" -> isUpdatedAt = true
                }
            }
            val resolved = param.type.resolve()
            val qn = resolved.declaration.qualifiedName?.asString() ?: "Any"
            val propType = qn.substringAfterLast('.') + if (resolved.isMarkedNullable) "?" else ""
            list.add(EntityColumnInfo(propName, colName, isId, propType, isSoftDelete, isCreatedAt, isUpdatedAt, idAutoGenerate))
        }
        return list
    }

    private fun generateMeta(
        pkg: String,
        entityName: String,
        entityPkg: String,
        tableName: String,
        columns: List<EntityColumnInfo>,
        idColumn: String
    ) {
        val entityRef = if (pkg == entityPkg) entityName else "$entityPkg.$entityName"
        val colsList = columns.joinToString(", ") { "\"${it.columnName}\"" }
        val typeEntries = columns.joinToString(", ") { col ->
            val baseType = col.propType.removeSuffix("?")
            val sqlType = when (baseType) {
                "Long", "kotlin.Long" -> "BIGINT"
                "Int", "kotlin.Int" -> "INTEGER"
                "Double", "kotlin.Double" -> "DOUBLE"
                "Float", "kotlin.Float" -> "FLOAT"
                "Boolean", "kotlin.Boolean" -> "INTEGER"
                else -> "TEXT"
            }
            "\"${col.columnName}\" to \"$sqlType\""
        }
        val file = codeGenerator.createNewFile(Dependencies(true), pkg, "${entityName}Meta")
        OutputStreamWriter(file).use { w ->
            w.write(
                """
// AUTO-GENERATED - DO NOT EDIT
package $pkg

import neton.database.api.EntityMeta

internal object ${entityName}Meta : EntityMeta<$entityRef> {
    override val table = "$tableName"
    override val idColumn = "$idColumn"
    override val columns = listOf($colsList)
    override val columnTypes = mapOf($typeEntries)
}
""".trimIndent()
            )
        }
    }

    private fun generateRowMapper(pkg: String, entityName: String, entityPkg: String, columns: List<EntityColumnInfo>) {
        val entityRef = if (pkg == entityPkg) entityName else "$entityPkg.$entityName"
        val mappings = columns.map { col ->
            val nullable = col.propType.endsWith("?")
            val baseType = col.propType.removeSuffix("?")
            // sqlx4k Column API: .asString() / .asStringOrNull() + Kotlin type conversion
            val getter = "row.get(\"${col.columnName}\")"
            val rhs = when {
                col.isId && nullable -> "$getter.asStringOrNull()?.toLong()"
                col.isId -> "$getter.asString().toLong()"
                baseType == "kotlin.Long" || baseType == "Long" -> {
                    if (nullable) "$getter.asStringOrNull()?.toLong()" else "$getter.asString().toLong()"
                }

                baseType == "kotlin.Int" || baseType == "Int" -> {
                    if (nullable) "$getter.asStringOrNull()?.toInt()" else "$getter.asString().toInt()"
                }

                baseType == "kotlin.Double" || baseType == "Double" -> {
                    if (nullable) "$getter.asStringOrNull()?.toDouble()" else "$getter.asString().toDouble()"
                }

                baseType == "kotlin.Float" || baseType == "Float" -> {
                    if (nullable) "$getter.asStringOrNull()?.toFloat()" else "$getter.asString().toFloat()"
                }

                baseType == "kotlin.Boolean" || baseType == "Boolean" -> {
                    if (nullable) "$getter.asStringOrNull()?.toBoolean()" else "$getter.asString().toBoolean()"
                }

                else -> {
                    if (nullable) "$getter.asStringOrNull()" else "$getter.asString()"
                }
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

    private fun generateTable(
        pkg: String,
        entityName: String,
        entityPkg: String,
        tableName: String,
        columns: List<EntityColumnInfo>,
        idColumn: String,
        idType: String,
        hasFacade: Boolean = false
    ) {
        val entityRef = if (pkg == entityPkg) entityName else "$entityPkg.$entityName"
        val toParamsEntries =
            columns.map { "\"${it.columnName}\" to it.${it.propName}" }.joinToString(",\n            ")

        val softDeleteCol = columns.find { it.isSoftDelete }
        val softDeleteParam = if (softDeleteCol != null) {
            val baseType = softDeleteCol.propType.removeSuffix("?")
            val notDeletedValue = when (baseType) {
                "Int", "kotlin.Int", "Long", "kotlin.Long" -> "0"
                else -> "false"
            }
            ",\n    softDeleteConfig = neton.database.api.SoftDeleteConfig(deletedColumn = \"${softDeleteCol.columnName}\", notDeletedValue = $notDeletedValue)"
        } else ""

        val createdAtCol = columns.find { it.isCreatedAt }
        val updatedAtCol = columns.find { it.isUpdatedAt }
        val autoFillParam = if (createdAtCol != null || updatedAtCol != null) {
            val args = mutableListOf<String>()
            createdAtCol?.let { args.add("createdAtColumn = \"${it.columnName}\"") }
            updatedAtCol?.let { args.add("updatedAtColumn = \"${it.columnName}\"") }
            ",\n    autoFillConfig = neton.database.api.AutoFillConfig(${args.joinToString(", ")})"
        } else ""

        // @Id(autoGenerate=false) 的 entity 需要让 adapter 知道：INSERT 时不要过滤掉 id
        // 字段（默认 true 是自增主键时由 DB 生成，这里默认值兼容历史）。
        val idColInfo = columns.find { it.isId }
        val autoGenerateParam = if (idColInfo != null && !idColInfo.idAutoGenerate) {
            ",\n    autoGenerateId = false"
        } else ""

        if (hasFacade) {
            // 用户已手写 XxxTable Facade → 生成 XxxTableImpl（internal）
            val file = codeGenerator.createNewFile(Dependencies(true), pkg, "${entityName}TableImpl")
            OutputStreamWriter(file).use { w ->
                w.write(
                    """
// AUTO-GENERATED - DO NOT EDIT
// 用户已手写 ${entityName}Table Facade，此处仅生成内部实现
package $pkg

import neton.database.api.Table
import neton.database.adapter.sqlx.SqlxTableAdapter
import neton.database.adapter.sqlx.SqlxDatabase

internal object ${entityName}TableImpl : Table<$entityRef, $idType> by SqlxTableAdapter<$entityRef, $idType>(
    meta = ${entityName}Meta,
    dbProvider = { SqlxDatabase.require() },
    mapper = ${entityName}RowMapper,
    toParams = { it -> mapOf(
            $toParamsEntries
    )},
    getId = { it.id }$softDeleteParam$autoFillParam$autoGenerateParam
)
""".trimIndent()
                )
            }
        } else {
            // 默认模式：直接生成 XxxTable（向后兼容）
            val file = codeGenerator.createNewFile(Dependencies(true), pkg, "${entityName}Table")
            OutputStreamWriter(file).use { w ->
                w.write(
                    """
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
    getId = { it.id }$softDeleteParam$autoFillParam$autoGenerateParam
)
""".trimIndent()
                )
            }
        }
    }

    private fun generateExtensions(
        pkg: String,
        entityName: String,
        entityPkg: String,
        columns: List<EntityColumnInfo>,
        idType: String,
        hasFacade: Boolean = false,
        facadePkg: String? = null
    ) {
        val entityRef = if (pkg == entityPkg) entityName else "$entityPkg.$entityName"
        val nonId = columns.filter { !it.isId && it.propName != "id" }
        val scopeVars = nonId.joinToString("\n    ") { "var ${it.propName}: ${it.propType}" }
        val scopeInit = nonId.joinToString("\n        ") { "${it.propName} = initial.${it.propName}" }
        val copyArgs = nonId.joinToString(", ") { "${it.propName} = scope.${it.propName}" }
        // Facade 不在 entity 同包时，需要显式 import
        val facadeImport = if (hasFacade && facadePkg != null && facadePkg != pkg) {
            "\nimport $facadePkg.${entityName}Table"
        } else ""
        val file = codeGenerator.createNewFile(Dependencies(true), pkg, "${entityName}Extensions")
        OutputStreamWriter(file).use { w ->
            w.write(
                """
// AUTO-GENERATED - DO NOT EDIT
// 定型 API：UserTable.get(id) / UserTable.destroy(id) / UserTable.update(id){ } / UserTable.query{ } / user.save() / user.delete()
// update(id){ } 为 mutate 风格：lambda 内直接赋值，copy 由框架内部生成
package $pkg

import neton.database.api.Predicate
import neton.database.api.PredicateScope
import neton.database.api.Query$facadeImport

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
""".trimIndent()
            )
        }
    }

    private fun generateTableDef(
        pkg: String,
        entityName: String,
        entityPkg: String,
        tableName: String,
        columns: List<EntityColumnInfo>
    ) {
        val entityRef = if (pkg == entityPkg) entityName else "$entityPkg.$entityName"

        // 生成每个列的 Column<Entity, Type> 定义
        val colDefs = columns.joinToString("\n    ") { col ->
            val baseType = col.propType.removeSuffix("?")
            val columnType = when (baseType) {
                "Long", "kotlin.Long" -> "ColumnType.LONG"
                "Int", "kotlin.Int" -> "ColumnType.INT"
                "Double", "kotlin.Double" -> "ColumnType.DOUBLE"
                "Float", "kotlin.Float" -> "ColumnType.DOUBLE"  // Float → DOUBLE
                "Boolean", "kotlin.Boolean" -> "ColumnType.BOOLEAN"
                "ByteArray", "kotlin.ByteArray" -> "ColumnType.BYTES"
                else -> "ColumnType.STRING"
            }
            val nullable = col.propType.endsWith("?")
            "val ${col.propName} = Column<$entityRef, ${col.propType}>(this, \"${col.columnName}\", \"${col.propName}\", $columnType, nullable = $nullable)"
        }

        // columns list
        val colsList = columns.joinToString(", ") { it.propName }

        // propMap 映射
        val propMapEntries = columns.joinToString(",\n        ") {
            "\"${it.propName}\" to ${it.propName}"
        }

        // TableRef<T> 扩展属性：JOIN 场景简写 U.id 代替 U[Entity::prop]
        val tableRefExtensions = columns.joinToString("\n") { col ->
            "val neton.database.dsl.TableRef<$entityRef>.${col.propName}: neton.database.dsl.ColRef<$entityRef, ${col.propType}>\n    get() = this[$entityRef::${col.propName}]"
        }

        val file = codeGenerator.createNewFile(Dependencies(true), pkg, "${entityName}TableDef")
        OutputStreamWriter(file).use { w ->
            w.write(
                """
// AUTO-GENERATED - DO NOT EDIT
package $pkg

import kotlin.reflect.KProperty1
import neton.database.dsl.Column
import neton.database.dsl.ColumnType
import neton.database.dsl.TableDef

internal object ${entityName}TableDef : TableDef<$entityRef> {
    override val tableName = "$tableName"

    $colDefs

    override val columns = listOf($colsList)

    // KSP 生成的精确映射，不依赖 runtime regex
    private val propMap: Map<String, Column<$entityRef, *>> = mapOf(
        $propMapEntries
    )

    @Suppress("UNCHECKED_CAST")
    override fun <V> resolve(prop: KProperty1<$entityRef, V>): Column<$entityRef, V> =
        (propMap[prop.name] as? Column<$entityRef, V>)
            ?: throw IllegalArgumentException("Unknown property: ${'$'}{prop.name}")
}

// TableRef 扩展属性（JOIN 场景简写：U.id 代替 U[Entity::id]）
$tableRefExtensions
""".trimIndent()
            )
        }
    }

    private fun generateEntityMapper(
        pkg: String,
        entityName: String,
        entityPkg: String,
        columns: List<EntityColumnInfo>
    ) {
        val entityRef = if (pkg == entityPkg) entityName else "$entityPkg.$entityName"

        // 生成映射代码：row.long("column") / row.stringOrNull("column")
        val mappings = columns.map { col ->
            val nullable = col.propType.endsWith("?")
            val baseType = col.propType.removeSuffix("?")
            val getter = when {
                baseType == "Long" || baseType == "kotlin.Long" -> {
                    if (nullable) "row.longOrNull(\"${col.columnName}\")" else "row.long(\"${col.columnName}\")"
                }
                baseType == "Int" || baseType == "kotlin.Int" -> {
                    if (nullable) "row.intOrNull(\"${col.columnName}\")" else "row.int(\"${col.columnName}\")"
                }
                baseType == "Double" || baseType == "kotlin.Double" -> {
                    if (nullable) "row.doubleOrNull(\"${col.columnName}\")" else "row.double(\"${col.columnName}\")"
                }
                baseType == "Boolean" || baseType == "kotlin.Boolean" -> {
                    if (nullable) "row.booleanOrNull(\"${col.columnName}\")" else "row.boolean(\"${col.columnName}\")"
                }
                baseType == "ByteArray" || baseType == "kotlin.ByteArray" -> {
                    if (nullable) "row.bytesOrNull(\"${col.columnName}\")" else "row.bytes(\"${col.columnName}\")"
                }
                else -> {
                    if (nullable) "row.stringOrNull(\"${col.columnName}\")" else "row.string(\"${col.columnName}\")"
                }
            }
            "${col.propName} = $getter"
        }.joinToString(",\n        ")

        val file = codeGenerator.createNewFile(Dependencies(true), pkg, "${entityName}EntityMapper")
        OutputStreamWriter(file).use { w ->
            w.write(
                """
// AUTO-GENERATED - DO NOT EDIT
package $pkg

import neton.database.api.EntityMapper
import neton.database.api.Row

internal object ${entityName}EntityMapper : EntityMapper<$entityRef> {
    override fun map(row: Row): $entityRef = $entityRef(
        $mappings
    )
}
""".trimIndent()
            )
        }
    }
}

class EntityTableProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return EntityTableProcessor(environment.codeGenerator, environment.logger, environment.options)
    }
}
