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
import neton.ksp.validation.codegen.ValidatorGenerator
import java.io.OutputStreamWriter

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
 * 扫描带校验注解的类型（data class 属性或方法参数），生成 Validator 实现与 GeneratedValidatorRegistry。
 */
class ValidationProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {

    override fun process(resolver: com.google.devtools.ksp.processing.Resolver): List<KSAnnotated> {
        val models = mutableMapOf<String, ValidationModel>()

        for (annName in VALIDATION_ANNOTATIONS) {
            val symbols = resolver.getSymbolsWithAnnotation(annName).toList()
            for (sym in symbols) {
                when (sym) {
                    is KSPropertyDeclaration -> collectFromProperty(sym, annName, models)
                    is KSValueParameter -> collectFromParameter(sym, annName, models)
                    else -> { }
                }
            }
        }

        val modelList = models.values.toList()
        if (modelList.isEmpty()) return emptyList()

        logger.info("Neton Validation: found ${modelList.size} type(s) with validation annotations")
        generateValidatorsAndRegistry(modelList)
        return emptyList()
    }

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

    private fun ruleFromPropertyAnnotation(prop: KSPropertyDeclaration, annName: String, propName: String): ValidationRule? {
        val short = annName.substringAfterLast(".")
        val ann = prop.annotations.firstOrNull { it.shortName.asString() == short } ?: return null
        val typeQualified = prop.type.resolve().declaration.qualifiedName?.asString()
        val isNullable = prop.type.resolve().isMarkedNullable
        return ruleFromAnn(ann, propName, typeQualified, isNullable)
    }

    private fun ruleFromParameterAnnotation(param: KSValueParameter, annName: String, paramName: String): ValidationRule? {
        val short = annName.substringAfterLast(".")
        val ann = param.annotations.firstOrNull { it.shortName.asString() == short } ?: return null
        val typeQualified = (param.type.resolve().declaration as? KSClassDeclaration)?.qualifiedName?.asString()
        val isNullable = param.type.resolve().isMarkedNullable
        return ruleFromAnn(ann, paramName, typeQualified, isNullable)
    }

    private fun ruleFromAnn(ann: com.google.devtools.ksp.symbol.KSAnnotation, propName: String, propertyTypeQualified: String? = null, isNullable: Boolean = false): ValidationRule? {
        val simpleName = ann.shortName.asString()
        val message = ann.arguments.find { it.name?.asString() == "message" }?.value as? String
            ?: defaultMessage(simpleName)
        val valueArgs = when (simpleName) {
            "Min", "Max" -> listOf((ann.arguments.find { it.name?.asString() == "value" }?.value as? Number)?.toString() ?: "0")
            "Size" -> listOf(
                (ann.arguments.find { it.name?.asString() == "min" }?.value as? Number)?.toString() ?: "0",
                (ann.arguments.find { it.name?.asString() == "max" }?.value as? Number)?.toString() ?: "Long.MAX_VALUE"
            )
            "Pattern" -> listOf(ann.arguments.find { it.name?.asString() == "regex" }?.value as? String ?: "")
            else -> emptyList()
        }
        return ValidationRule(propertyName = propName, annotationSimpleName = simpleName, message = message, constraintCode = simpleName, valueArgs = valueArgs, propertyTypeQualified = propertyTypeQualified, isNullable = isNullable)
    }

    private fun defaultMessage(simpleName: String): String = when (simpleName) {
        "NotBlank" -> "must not be blank"
        "NotNull" -> "must not be null"
        "Min" -> "must be >= {value}"
        "Max" -> "must be <= {value}"
        "Email" -> "invalid email"
        else -> "validation failed"
    }

    private fun generateValidatorsAndRegistry(models: List<ValidationModel>) {
        val dependencies = com.google.devtools.ksp.processing.Dependencies(true)
        val file = codeGenerator.createNewFile(
            dependencies = dependencies,
            packageName = "neton.validation.generated",
            fileName = "GeneratedValidatorRegistry"
        )
        OutputStreamWriter(file).use { writer ->
            writer.write("""
                // AUTO-GENERATED by Neton KSP ValidationProcessor - DO NOT EDIT
                package neton.validation.generated

                import neton.validation.ValidatorRegistry
                import neton.validation.internal.DefaultValidatorRegistry
                import neton.core.http.ValidationError
                import neton.core.component.NetonContext

                """.trimIndent())
            models.forEach { writer.write("import ${it.qualifiedName}\n") }
            models.forEach { ValidatorGenerator.generateValidator(writer, it) }
            writer.write("""

                object GeneratedValidatorRegistry {
                    fun default(): ValidatorRegistry = DefaultValidatorRegistry(
                        mapOf(
                            ${ValidatorGenerator.generateRegistryEntries(models)}
                        )
                    )

                    /** 由 GeneratedInitializer 或应用在启动时调用，避免校验静默失效 */
                    fun bindTo(ctx: NetonContext) {
                        ctx.bindIfAbsent(ValidatorRegistry::class, default())
                    }
                }
                """.trimIndent())
        }
    }
}

class ValidationProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return ValidationProcessor(environment.codeGenerator, environment.logger)
    }
}
