package neton.ksp.validation

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.isConstructor
import com.google.devtools.ksp.symbol.Origin
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Visibility
import neton.ksp.MACHINE_CODE
import neton.ksp.ModuleFragmentSink
import neton.ksp.validation.codegen.ValidatorGenerator
import java.io.OutputStreamWriter

/**
 * 扫描带约束注解的类型（属性，或主构造里的 `val`/`var` 参数），为每个类型生成一个校验器：
 * 与类型同包的 `<Name>NetonValidator`（见 [validatorFqnFor]）。
 *
 * 路由代码生成按确定名字**直接调用**这个校验器（[validatorRefFor]），不经运行期注册表：
 * 有约束就一定校验，缺校验器就编译失败——没有「查不到就跳过」的静默路径。
 *
 * 约束注解只在请求体 DTO 的属性上生效；标在别处（处理器方法参数、普通函数参数）一律编译失败，
 * 不允许「写了但不生效」。
 */
class ValidationProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String> = emptyMap()
) : SymbolProcessor {

    private val moduleId: String? = options["neton.moduleId"]?.takeIf { it.isNotBlank() }
    private val generated = mutableSetOf<String>()
    private var generatedCount = 0

    private class Target(val owner: KSClassDeclaration, val name: String, val type: KSTypeReference, val node: KSAnnotated)

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val models = linkedMapOf<String, Pair<KSClassDeclaration, MutableList<ValidationRule>>>()
        val seen = mutableSetOf<Triple<String, String, String>>()

        for (annName in VALIDATION_ANNOTATIONS) {
            for (sym in resolver.getSymbolsWithAnnotation(annName)) {
                val target = targetOf(sym, annName) ?: continue
                val ownerName = target.owner.qualifiedName?.asString() ?: continue
                if (!seen.add(Triple(ownerName, target.name, annName))) continue
                val ann = sym.annotations.firstOrNull { it.qualifiedNameOrNull() == annName } ?: continue
                val rule = ruleFromAnn(resolver, ann, target) ?: continue
                models.getOrPut(ownerName) { target.owner to mutableListOf() }.second += rule
            }
        }

        val fresh = models.filterKeys { it !in generated }
        if (fresh.isEmpty()) return emptyList()
        logger.info("Neton Validation: found ${fresh.size} type(s) with validation annotations")

        fresh.forEach { (name, entry) ->
            val (decl, rules) = entry
            val visibility = effectiveVisibility(decl) ?: run {
                logger.error(
                    "Neton validation: '$name' carries constraint annotations but is private/local; " +
                        "a request-body DTO must be public or internal so its generated validator can reference it.",
                    decl,
                )
                return@forEach
            }
            val fqn = validatorFqnFor(decl)
            val model = ValidationModel(
                qualifiedName = name,
                simpleName = decl.simpleName.asString(),
                rules = rules,
                packageName = decl.packageName.asString(),
                validatorSimpleName = fqn.substringAfterLast('.'),
                visibility = visibility,
            )
            val file = codeGenerator.createNewFile(
                dependencies = Dependencies(false, *listOfNotNull(decl.containingFile).toTypedArray()),
                packageName = model.packageName,
                fileName = model.validatorSimpleName,
            )
            OutputStreamWriter(file).use { ValidatorGenerator.generateFile(it, model) }
            generated += name
        }
        generatedCount += fresh.size
        moduleId?.let { ModuleFragmentSink.addStat(it, "validators", generatedCount) }
        return emptyList()
    }

    /** 注解落点 → 被校验的属性；不在 DTO 属性上就报错（写了不生效的约束等于没写）。 */
    private fun targetOf(sym: KSAnnotated, annName: String): Target? {
        val short = annName.substringAfterLast('.')
        return when (sym) {
            is KSPropertyDeclaration -> {
                val owner = sym.parentDeclaration as? KSClassDeclaration
                if (owner == null) {
                    logger.error("Neton @$short on top-level property '${sym.simpleName.asString()}' is never enforced; " +
                        "constraints only apply to properties of a request-body class.", sym)
                    null
                } else Target(owner, sym.simpleName.asString(), sym.type, sym)
            }

            is KSValueParameter -> {
                val fn = sym.parent as? KSFunctionDeclaration
                val owner = fn?.parentDeclaration as? KSClassDeclaration
                // data class 合成的 copy() 带着同样的参数注解：属性那边已经收集过，这里跳过
                if (fn?.origin == Origin.SYNTHETIC) return null
                val isPrimaryCtor = fn != null && owner != null && fn.isConstructor() &&
                    owner.primaryConstructor?.parameters?.any { it.name == sym.name } == true
                val paramName = sym.name?.asString() ?: "?"
                if (isPrimaryCtor && (sym.isVal || sym.isVar)) {
                    Target(owner!!, paramName, sym.type, sym)
                } else {
                    logger.error(
                        "Neton @$short on parameter '$paramName' of ${fn?.qualifiedName?.asString() ?: "?"} is never enforced: " +
                            "constraint annotations only apply to properties of a request-body class. Move the parameter " +
                            "into a @Body DTO, or check it explicitly in the handler.",
                        sym,
                    )
                    null
                }
            }

            else -> {
                logger.error("Neton @$short is only supported on request-body class properties.", sym)
                null
            }
        }
    }

    private fun ruleFromAnn(resolver: Resolver, ann: KSAnnotation, target: Target): ValidationRule? {
        val simpleName = ann.shortName.asString()
        val propName = target.name
        val resolved = target.type.resolve()
        val typeQualified = resolved.declaration.qualifiedName?.asString()
        val isNullable = resolved.isMarkedNullable
        // 注解默认 message = ""：用约束的内建码。作者写了就必须是机器码——它原样成为字段错误的
        // code/message，客户端拿它查语言包；句子在这里直接让编译失败。
        val customCode = (ann.arguments.find { it.name?.asString() == "message" }?.value as? String)
            ?.takeIf { it.isNotEmpty() }
        if (customCode != null && !MACHINE_CODE.matches(customCode)) {
            logger.error(
                "Neton @$simpleName on '$propName': message=\"$customCode\" is not a machine code. " +
                    "Use an UPPER_SNAKE_CASE code (e.g. INVALID_MOBILE) or omit message to get the built-in code.",
                // 注解节点在部分 KSP 版本上没有位置信息；挂到所在属性上，报错带文件与行号
                target.node
            )
        }
        val valueArgs = when (simpleName) {
            "Min", "Max" -> listOf(
                (ann.arguments.find { it.name?.asString() == "value" }?.value as? Number)?.toString() ?: "0"
            )

            "Size" -> listOf(
                (ann.arguments.find { it.name?.asString() == "min" }?.value as? Number)?.toString() ?: "0",
                (ann.arguments.find { it.name?.asString() == "max" }?.value as? Number)?.toString() ?: Long.MAX_VALUE.toString()
            )

            "Pattern" -> listOf(ann.arguments.find { it.name?.asString() == "regex" }?.value as? String ?: "")
            else -> emptyList()
        }
        val owner = target.owner.qualifiedName?.asString()
        fun reject(reason: String): ValidationRule? {
            logger.error("Neton @$simpleName on $owner.$propName (type $typeQualified): $reason", target.node)
            return null
        }
        when (simpleName) {
            "Size" -> if (typeQualified !in ValidatorGenerator.SIZE_TYPES) {
                return reject("@Size supports String, collections, maps and arrays only.")
            }
            "Min", "Max" -> if (typeQualified !in INTEGRAL_TYPES) {
                return reject("@$simpleName supports numeric types only.")
            }
            "NotBlank", "Pattern", "Email" -> if (typeQualified != "kotlin.String") {
                return reject("@$simpleName supports String only.")
            }
        }

        var nestedRef: String? = null
        var nestedIsCollection = false
        if (simpleName == "Valid") {
            val nestedType = if (typeQualified in VALIDATABLE_COLLECTIONS) {
                nestedIsCollection = true
                resolved.arguments.firstOrNull()?.type?.resolve()
            } else resolved
            nestedRef = nestedType?.let { validatorRefFor(resolver, it) }
                ?: return reject("@Valid requires the nested type to declare constraint annotations (no validator exists for it).")
        }
        return ValidationRule(
            propertyName = propName,
            annotationSimpleName = simpleName,
            customCode = customCode,
            valueArgs = valueArgs,
            propertyTypeQualified = typeQualified,
            isNullable = isNullable,
            nestedValidatorFqn = nestedRef,
            nestedIsCollection = nestedIsCollection,
        )
    }

    /** 生成校验器的可见性：跟随类型（含外层类）的有效可见性；private/local 返回 null。 */
    private fun effectiveVisibility(decl: KSClassDeclaration): String? {
        var internal = false
        var cur: KSNode? = decl
        while (cur is KSDeclaration) {
            when (cur.getVisibility()) {
                Visibility.PRIVATE, Visibility.LOCAL, Visibility.PROTECTED -> return null
                Visibility.INTERNAL -> internal = true
                else -> {}
            }
            cur = cur.parentDeclaration
        }
        return if (internal) "internal" else "public"
    }

    private companion object {
        val INTEGRAL_TYPES = setOf("kotlin.Int", "kotlin.Long", "kotlin.Short", "kotlin.Byte", "kotlin.Double", "kotlin.Float")
    }
}

class ValidationProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return ValidationProcessor(environment.codeGenerator, environment.logger, environment.options)
    }
}
