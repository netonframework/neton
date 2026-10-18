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

/**
 * 框架内建字段码（与 neton-core `neton.core.http.ValidationCode` 一一对应；KSP 跑在 JVM 上，
 * 引用不到 Native 的 neton-core，这里按字面量维护，契约测试锁定）。
 */
internal object BuiltInValidationCode {
    const val REQUIRED = "REQUIRED"
    const val TOO_SHORT = "TOO_SHORT"
    const val TOO_LONG = "TOO_LONG"
    const val TOO_FEW_ITEMS = "TOO_FEW_ITEMS"
    const val TOO_MANY_ITEMS = "TOO_MANY_ITEMS"
    const val TOO_SMALL = "TOO_SMALL"
    const val TOO_LARGE = "TOO_LARGE"
    const val INVALID_FORMAT = "INVALID_FORMAT"
    const val INVALID_EMAIL = "INVALID_EMAIL"
}

/** 生成 Kotlin 字符串字面量内的转义（用于 Regex 等） */
private fun escapeKotlinStringLiteral(s: String): String =
    s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")

private const val EMAIL_REGEX = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\\\.[A-Za-z]{2,}\$"

/**
 * 根据 ValidationModel 生成 Validator 实现类源码。
 * 不依赖 Konform，直接生成 if/errors 代码。
 *
 * 每条违规产出 `ValidationError.of(path, CODE, params)`：`code` 与 `message` 都是机器码
 * （作者在注解上给的码，或约束的内建码），约束值（min/max）进 `params`，不拼句子。
 */
object ValidatorGenerator {

    fun generateValidator(writer: Writer, model: ValidationModel) {
        writer.write(
            """
            internal object ${model.simpleName}Validator : neton.validation.Validator<${model.simpleName}> {
        """.trimIndent()
        )
        val patternRules = model.rules
            .filter { it.annotationSimpleName == "Pattern" }
            .distinctBy { it.propertyName }
        patternRules.forEach { rule ->
            val raw = rule.valueArgs.firstOrNull() ?: ""
            val regex = escapeKotlinStringLiteral(raw)
            writer.write("                private val ${rule.propertyName}Pattern = Regex(\"$regex\")\n")
        }
        writer.write(
            """
                override fun validate(value: ${model.simpleName}): List<neton.core.http.ValidationError> {
                    val errors = mutableListOf<neton.core.http.ValidationError>()
        """.trimIndent()
        )
        model.rules.forEach { rule ->
            writer.write(generateRuleCheck(rule))
        }
        writer.write(
            """
                    return errors
                }
            }
        """.trimIndent()
        )
    }

    /** `errors += ValidationError.of("prop", "CODE", mapOf(...))` */
    private fun emit(rule: ValidationRule, builtInCode: String, params: Map<String, String> = emptyMap()): String {
        val code = rule.customCode ?: builtInCode
        val paramsExpr = if (params.isEmpty()) "" else
            ", mapOf(" + params.entries.joinToString(", ") { (k, v) -> "\"$k\" to ${v}L" } + ")"
        return "errors += neton.core.http.ValidationError.of(\"${rule.propertyName}\", \"$code\"$paramsExpr)"
    }

    /** 可空属性：值为 null 时跳过（除 NotBlank/NotNull 外，null 不算违反约束）。 */
    private fun guarded(rule: ValidationRule, valueVar: String, body: (String) -> String): String =
        if (rule.isNullable) """
                    value.${rule.propertyName}?.let { $valueVar ->
                        ${body(valueVar)}
                    }
                """ else """
                    run {
                        val $valueVar = value.${rule.propertyName}
                        ${body(valueVar)}
                    }
                """

    private fun generateRuleCheck(rule: ValidationRule): String {
        val p = rule.propertyName
        return when (rule.annotationSimpleName) {
            "NotBlank" -> {
                val cond = if (rule.isNullable) "value.$p == null || value.$p.isBlank()" else "value.$p.isBlank()"
                """
                    if ($cond) {
                        ${emit(rule, BuiltInValidationCode.REQUIRED)}
                    }
                """
            }

            "NotNull" -> """
                    if (value.$p == null) {
                        ${emit(rule, BuiltInValidationCode.REQUIRED)}
                    }
                """

            "Min" -> {
                val min = rule.valueArgs.firstOrNull() ?: "0"
                guarded(rule, "${p}Value") { v ->
                    "if ($v < ${min}L) { ${emit(rule, BuiltInValidationCode.TOO_SMALL, mapOf("min" to min))} }"
                }
            }

            "Max" -> {
                val max = rule.valueArgs.firstOrNull() ?: "0"
                guarded(rule, "${p}Value") { v ->
                    "if ($v > ${max}L) { ${emit(rule, BuiltInValidationCode.TOO_LARGE, mapOf("max" to max))} }"
                }
            }

            "Size" -> {
                val min = rule.valueArgs.getOrNull(0) ?: "0"
                val maxRaw = rule.valueArgs.getOrNull(1)
                val max = maxRaw?.takeIf { it != Long.MAX_VALUE.toString() }
                val fqn = rule.propertyTypeQualified
                val isString = fqn != null && fqn in SIZE_LENGTH_TYPES
                val isCollection = fqn != null && fqn in SIZE_COLLECTION_TYPES
                if (!isString && !isCollection) {
                    """
                    // @Size on $p (type $fqn) not supported; use String/List/Set/Map/Array
                """
                } else {
                    val member = if (isString) "length" else "size"
                    val (shortCode, longCode) = if (isString) {
                        BuiltInValidationCode.TOO_SHORT to BuiltInValidationCode.TOO_LONG
                    } else {
                        BuiltInValidationCode.TOO_FEW_ITEMS to BuiltInValidationCode.TOO_MANY_ITEMS
                    }
                    val params = buildMap {
                        put("min", min)
                        if (max != null) put("max", max)
                    }
                    guarded(rule, "${p}Value") { v ->
                        val n = "$v.$member"
                        val tooLong = if (max != null) " else if ($n > $max) { ${emit(rule, longCode, params)} }" else ""
                        "if ($n < $min) { ${emit(rule, shortCode, params)} }$tooLong"
                    }
                }
            }

            "Pattern" -> guarded(rule, "${p}Value") { v ->
                "if (!${p}Pattern.matches($v)) { ${emit(rule, BuiltInValidationCode.INVALID_FORMAT)} }"
            }

            "Email" -> guarded(rule, "${p}Value") { v ->
                "if (!Regex(\"$EMAIL_REGEX\").matches($v)) { ${emit(rule, BuiltInValidationCode.INVALID_EMAIL)} }"
            }

            "Valid" -> {
                val nestedSimpleName = rule.propertyTypeQualified?.substringAfterLast(".") ?: "Unknown"
                if (rule.isNullable) {
                    """
                    value.$p?.let { nested ->
                        ${nestedSimpleName}Validator.validate(nested).forEach { err ->
                            errors += err.copy(path = "$p." + err.path)
                        }
                    }
                """
                } else {
                    """
                    ${nestedSimpleName}Validator.validate(value.$p).forEach { err ->
                        errors += err.copy(path = "$p." + err.path)
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
