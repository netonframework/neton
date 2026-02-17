package neton.ksp.validation.codegen

import neton.ksp.validation.ValidationModel
import neton.ksp.validation.ValidationRule
import java.io.Writer

/** Size 支持的属性类型 FQN：String→length，其余→size */
private val SIZE_LENGTH_TYPES = setOf("kotlin.String")
private val SIZE_COLLECTION_TYPES = setOf(
    "kotlin.collections.List", "kotlin.collections.MutableList",
    "kotlin.collections.Set", "kotlin.collections.MutableSet",
    "kotlin.collections.Map", "kotlin.collections.MutableMap",
    "kotlin.collections.Collection", "kotlin.collections.MutableCollection",
    "kotlin.Array", "kotlin.IntArray", "kotlin.LongArray", "kotlin.DoubleArray",
    "kotlin.FloatArray", "kotlin.BooleanArray", "kotlin.ByteArray", "kotlin.ShortArray", "kotlin.CharArray"
)

/** 生成 Kotlin 字符串字面量内的转义（用于 Regex 等） */
private fun escapeKotlinStringLiteral(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")

/**
 * 根据 ValidationModel 生成 Validator 实现类源码。
 * 不依赖 Konform，直接生成 if/errors 代码。
 */
object ValidatorGenerator {

    fun generateValidator(writer: Writer, model: ValidationModel) {
        writer.write(
            """
            internal object ${model.simpleName}Validator : neton.validation.Validator<${model.simpleName}> {
        """.trimIndent()
        )
        val patternRules = model.rules.filter { it.annotationSimpleName == "Pattern" }
        patternRules.forEach { rule ->
            val raw = rule.valueArgs.firstOrNull() ?: ""
            val regex = escapeKotlinStringLiteral(raw)
            writer.write("                private val ${rule.propertyName}_Pattern = Regex(\"$regex\")\n")
        }
        writer.write(
            """
                override fun validate(v: ${model.simpleName}): List<neton.core.http.ValidationError> {
                    val errors = mutableListOf<neton.core.http.ValidationError>()
        """.trimIndent()
        )
        model.rules.forEach { rule ->
            writer.write(generateRuleCheck(model.simpleName, rule))
        }
        writer.write(
            """
                    return errors
                }
            }
        """.trimIndent()
        )
    }

    private fun generateRuleCheck(typeSimpleName: String, rule: ValidationRule): String {
        return when (rule.annotationSimpleName) {
            "NotBlank" -> {
                val cond =
                    if (rule.isNullable) "v.${rule.propertyName} == null || v.${rule.propertyName}.isBlank()" else "v.${rule.propertyName}.isBlank()"
                """
                    if ($cond) {
                        errors += neton.core.http.ValidationError(path = "${rule.propertyName}", message = "${rule.message}", code = "${rule.constraintCode}")
                    }
                """
            }

            "NotNull" -> """
                    if (v.${rule.propertyName} == null) {
                        errors += neton.core.http.ValidationError(path = "${rule.propertyName}", message = "${rule.message}", code = "${rule.constraintCode}")
                    }
                """

            "Min" -> """
                    if (v.${rule.propertyName} < ${rule.valueArgs.firstOrNull() ?: "0"}L) {
                        errors += neton.core.http.ValidationError(path = "${rule.propertyName}", message = "${rule.message}", code = "${rule.constraintCode}")
                    }
                """

            "Max" -> """
                    if (v.${rule.propertyName} > ${rule.valueArgs.firstOrNull() ?: "0"}L) {
                        errors += neton.core.http.ValidationError(path = "${rule.propertyName}", message = "${rule.message}", code = "${rule.constraintCode}")
                    }
                """

            "Size" -> {
                val min = rule.valueArgs.getOrNull(0) ?: "0"
                val max = rule.valueArgs.getOrNull(1) ?: "Long.MAX_VALUE"
                val fqn = rule.propertyTypeQualified
                val (sizeExpr, supported) = when {
                    fqn != null && fqn in SIZE_LENGTH_TYPES -> "v.${rule.propertyName}.length" to true
                    fqn != null && fqn in SIZE_COLLECTION_TYPES -> "v.${rule.propertyName}.size" to true
                    else -> "0" to false
                }
                if (supported)
                    """
                    val _size = $sizeExpr
                    if (_size < $min || _size > $max) {
                        errors += neton.core.http.ValidationError(path = "${rule.propertyName}", message = "${rule.message}", code = "${rule.constraintCode}")
                    }
                """
                else
                    """
                    // @Size on ${rule.propertyName} (type $fqn) not supported; use String/List/Set/Map/Array
                """
            }

            "Pattern" -> """
                    if (!${rule.propertyName}_Pattern.matches(v.${rule.propertyName})) {
                        errors += neton.core.http.ValidationError(path = "${rule.propertyName}", message = "${rule.message}", code = "${rule.constraintCode}")
                    }
                """

            "Email" -> """
                    if (!Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\\\.[A-Za-z]{2,}\$").matches(v.${rule.propertyName})) {
                        errors += neton.core.http.ValidationError(path = "${rule.propertyName}", message = "${rule.message}", code = "${rule.constraintCode}")
                    }
                """

            "Valid" -> {
                val nestedSimpleName = rule.propertyTypeQualified?.substringAfterLast(".") ?: "Unknown"
                if (rule.isNullable) {
                    """
                    v.${rule.propertyName}?.let { nested ->
                        ${nestedSimpleName}Validator.validate(nested).forEach { err ->
                            errors += neton.core.http.ValidationError(path = "${rule.propertyName}." + err.path, message = err.message, code = err.code)
                        }
                    }
                """
                } else {
                    """
                    ${nestedSimpleName}Validator.validate(v.${rule.propertyName}).forEach { err ->
                        errors += neton.core.http.ValidationError(path = "${rule.propertyName}." + err.path, message = err.message, code = err.code)
                    }
                """
                }
            }

            else -> """
                    // ${rule.annotationSimpleName} (custom or unimplemented)
                """
        }
    }

    /** 生成 GeneratedValidatorRegistry 的 default() 方法体 */
    fun generateRegistryEntries(models: List<ValidationModel>): String {
        return models.joinToString(",\n") { m ->
            "${m.qualifiedName}::class to ${m.simpleName}Validator"
        }
    }
}
