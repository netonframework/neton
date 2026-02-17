package neton.ksp.validation

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSValueParameter
import neton.ksp.ModuleFragmentSink
import neton.ksp.validation.codegen.ValidatorGenerator
import java.io.OutputStreamWriter
import java.io.StringWriter

private val VALIDATION_ANNOTATIONS = setOf(
    "neton.validation.annotations.NotBlank",
    "neton.validation.annotations.NotNull",
    "neton.validation.annotations.Min",
    "neton.validation.annotations.Max",
    "neton.validation.annotations.Size",
    "neton.validation.annotations.Pattern",
    "neton.validation.annotations.Email",
    "neton.validation.annotations.Valid"
)

/**
 * 扫描带校验注解的类型（data class 属性或方法参数），生成 Validator 实现与 ValidatorRegistry。
 *
 * 模块模式（moduleId 存在）：不生成独立文件，将 Validator 类和 Registry 写入 ModuleFragmentSink。
 * 兼容模式（moduleId 不存在）：生成 GeneratedValidatorRegistry 到 neton.validation.generated。
 */
class ValidationProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String> = emptyMap()
) : SymbolProcessor {

    private val moduleId: String? = options["neton.moduleId"]?.takeIf { it.isNotBlank() }

    override fun process(resolver: com.google.devtools.ksp.processing.Resolver): List<KSAnnotated> {
        val models = mutableMapOf<String, ValidationModel>()

        for (annName in VALIDATION_ANNOTATIONS) {
            val symbols = resolver.getSymbolsWithAnnotation(annName).toList()
            for (sym in symbols) {
                when (sym) {
                    is KSPropertyDeclaration -> collectFromProperty(sym, annName, models)
                    is KSValueParameter -> collectFromParameter(sym, annName, models)
                    else -> {}
                }
            }
        }

        val modelList = models.values.toList()
        if (modelList.isEmpty()) return emptyList()

        logger.info("Neton Validation: found ${modelList.size} type(s) with validation annotations")

        if (moduleId != null) {
            writeToSink(modelList)
        } else {
            generateValidatorsAndRegistry(modelList)
        }
        return emptyList()
    }

    // region --- 收集 ---

    private fun collectFromProperty(
        prop: KSPropertyDeclaration,
        annName: String,
        models: MutableMap<String, ValidationModel>
    ) {
        val container = prop.parentDeclaration as? KSClassDeclaration ?: return
        val typeName = container.qualifiedName?.asString() ?: return
        val propName = prop.simpleName.asString()
        val rule = ruleFromPropertyAnnotation(prop, annName, propName) ?: return
        val existing = models[typeName]
        val newModel = if (existing != null) {
            existing.copy(rules = existing.rules + rule)
        } else {
            ValidationModel(
                qualifiedName = typeName,
                simpleName = container.simpleName.asString(),
                rules = listOf(rule)
            )
        }
        models[typeName] = newModel
    }

    private fun collectFromParameter(
        param: KSValueParameter,
        annName: String,
        models: MutableMap<String, ValidationModel>
    ) {
        val typeDecl = param.type.resolve().declaration
        if (typeDecl !is KSClassDeclaration) return
        val typeName = typeDecl.qualifiedName?.asString() ?: return
        val paramName = param.name?.asString() ?: return
        val rule = ruleFromParameterAnnotation(param, annName, paramName) ?: return
        val simpleName = typeDecl.simpleName.asString()
        val existing = models[typeName]
        models[typeName] = if (existing != null) {
            existing.copy(rules = existing.rules + rule)
        } else {
            ValidationModel(qualifiedName = typeName, simpleName = simpleName, rules = listOf(rule))
        }
    }

    private fun ruleFromPropertyAnnotation(
        prop: KSPropertyDeclaration,
        annName: String,
        propName: String
    ): ValidationRule? {
        val short = annName.substringAfterLast(".")
        val ann = prop.annotations.firstOrNull { it.shortName.asString() == short } ?: return null
        val typeQualified = prop.type.resolve().declaration.qualifiedName?.asString()
        val isNullable = prop.type.resolve().isMarkedNullable
        return ruleFromAnn(ann, propName, typeQualified, isNullable)
    }

    private fun ruleFromParameterAnnotation(
        param: KSValueParameter,
        annName: String,
        paramName: String
    ): ValidationRule? {
        val short = annName.substringAfterLast(".")
        val ann = param.annotations.firstOrNull { it.shortName.asString() == short } ?: return null
        val typeQualified = (param.type.resolve().declaration as? KSClassDeclaration)?.qualifiedName?.asString()
        val isNullable = param.type.resolve().isMarkedNullable
        return ruleFromAnn(ann, paramName, typeQualified, isNullable)
    }

    private fun ruleFromAnn(
        ann: com.google.devtools.ksp.symbol.KSAnnotation,
        propName: String,
        propertyTypeQualified: String? = null,
        isNullable: Boolean = false
    ): ValidationRule? {
        val simpleName = ann.shortName.asString()
        val message = ann.arguments.find { it.name?.asString() == "message" }?.value as? String
            ?: defaultMessage(simpleName)
        val valueArgs = when (simpleName) {
            "Min", "Max" -> listOf(
                (ann.arguments.find { it.name?.asString() == "value" }?.value as? Number)?.toString() ?: "0"
            )

            "Size" -> listOf(
                (ann.arguments.find { it.name?.asString() == "min" }?.value as? Number)?.toString() ?: "0",
                (ann.arguments.find { it.name?.asString() == "max" }?.value as? Number)?.toString() ?: "Long.MAX_VALUE"
            )

            "Pattern" -> listOf(ann.arguments.find { it.name?.asString() == "regex" }?.value as? String ?: "")
            else -> emptyList()
        }
        return ValidationRule(
            propertyName = propName,
            annotationSimpleName = simpleName,
            message = message,
            constraintCode = simpleName,
            valueArgs = valueArgs,
            propertyTypeQualified = propertyTypeQualified,
            isNullable = isNullable
        )
    }

    private fun defaultMessage(simpleName: String): String = when (simpleName) {
        "NotBlank" -> "must not be blank"
        "NotNull" -> "must not be null"
        "Min" -> "must be >= {value}"
        "Max" -> "must be <= {value}"
        "Email" -> "invalid email"
        else -> "validation failed"
    }

    // endregion

    // region --- 模块模式：写片段到 sink ---

    private fun writeToSink(models: List<ValidationModel>) {
        ModuleFragmentSink.addStat(moduleId!!, "validators", models.size)
        ModuleFragmentSink.addImports(
            moduleId!!,
            "import neton.validation.ValidatorRegistry",
            "import neton.validation.internal.DefaultValidatorRegistry",
            "import neton.core.http.ValidationError"
        )
        models.forEach { ModuleFragmentSink.addImport(moduleId, "import ${it.qualifiedName}") }

        // 将 Validator 类写为顶层声明
        models.forEach { model ->
            val sw = StringWriter()
            ValidatorGenerator.generateValidator(sw, model)
            ModuleFragmentSink.addTopLevelDeclaration(moduleId, sw.toString())
        }

        // 将 registry 注册写为 fragment
        val entries = ValidatorGenerator.generateRegistryEntries(models)
        val code = buildString {
            appendLine("        val validatorRegistry = DefaultValidatorRegistry(mapOf(")
            appendLine("            $entries")
            appendLine("        ))")
            append("        ctx.bindIfAbsent(ValidatorRegistry::class, validatorRegistry)")
        }
        ModuleFragmentSink.addFragment(moduleId, "validators", "注册校验器", code)
    }

    // endregion

    // region --- 兼容模式：生成独立文件 ---

    private fun generateValidatorsAndRegistry(models: List<ValidationModel>) {
        val dependencies = com.google.devtools.ksp.processing.Dependencies(true)
        val file = codeGenerator.createNewFile(
            dependencies = dependencies,
            packageName = "neton.validation.generated",
            fileName = "GeneratedValidatorRegistry"
        )
        OutputStreamWriter(file).use { writer ->
            writer.write(
                """
                // AUTO-GENERATED by Neton KSP ValidationProcessor - DO NOT EDIT
                package neton.validation.generated

                import neton.validation.ValidatorRegistry
                import neton.validation.internal.DefaultValidatorRegistry
                import neton.core.http.ValidationError
                import neton.core.component.NetonContext

                """.trimIndent()
            )
            models.forEach { writer.write("import ${it.qualifiedName}\n") }
            models.forEach { ValidatorGenerator.generateValidator(writer, it) }
            writer.write(
                """

                object GeneratedValidatorRegistry {
                    fun default(): ValidatorRegistry = DefaultValidatorRegistry(
                        mapOf(
                            ${ValidatorGenerator.generateRegistryEntries(models)}
                        )
                    )

                    fun bindTo(ctx: NetonContext) {
                        ctx.bindIfAbsent(ValidatorRegistry::class, default())
                    }
                }
                """.trimIndent()
            )
        }
    }

    // endregion
}

class ValidationProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return ValidationProcessor(environment.codeGenerator, environment.logger, environment.options)
    }
}
