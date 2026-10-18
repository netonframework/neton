package neton.ksp.validation

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType

/** 约束注解（全限定名）。处理器按名字识别，不依赖 Native-only 的 neton-validation。 */
internal val VALIDATION_ANNOTATIONS = setOf(
    "neton.validation.annotations.NotBlank",
    "neton.validation.annotations.NotNull",
    "neton.validation.annotations.Min",
    "neton.validation.annotations.Max",
    "neton.validation.annotations.Size",
    "neton.validation.annotations.Pattern",
    "neton.validation.annotations.Email",
    "neton.validation.annotations.Valid"
)

internal fun KSAnnotation.qualifiedNameOrNull(): String? =
    annotationType.resolve().declaration.qualifiedName?.asString()

internal fun KSAnnotated.constraintAnnotations(): List<KSAnnotation> =
    annotations.filter { it.qualifiedNameOrNull() in VALIDATION_ANNOTATIONS }.toList()

/**
 * DTO 的生成校验器全限定名：与 DTO 同包，`<去包名的嵌套名，点换下划线>NetonValidator`。
 *
 * 名字是确定的，所以路由代码生成可以**按名直接引用**校验器——缺了就编译失败，
 * 不存在运行期「查不到就跳过」的路径。
 */
internal fun validatorFqnFor(decl: KSClassDeclaration): String {
    val pkg = decl.packageName.asString()
    val qualified = decl.qualifiedName?.asString() ?: decl.simpleName.asString()
    val local = if (pkg.isEmpty()) qualified else qualified.removePrefix("$pkg.")
    val simple = local.replace('.', '_') + "NetonValidator"
    return if (pkg.isEmpty()) simple else "$pkg.$simple"
}

/** 类型本身（属性或主构造参数）是否声明了约束注解。只对本编译单元内的源码声明可靠（注解是 SOURCE 保留）。 */
internal fun hasConstraints(decl: KSClassDeclaration): Boolean {
    if (decl.getAllProperties().any { it.constraintAnnotations().isNotEmpty() }) return true
    return decl.primaryConstructor?.parameters?.any { it.constraintAnnotations().isNotEmpty() } == true
}

/**
 * 某个类型的校验器引用（全限定名），没有约束时返回 null。
 *
 * - 本编译单元的类型：看源码上有没有约束注解——有就一定会生成校验器（同一次 KSP 产出）。
 * - 依赖里的类型：按确定名字查依赖里由它所在模块生成的校验器。
 */
internal fun validatorRefFor(resolver: Resolver, type: KSType): String? {
    val decl = type.declaration as? KSClassDeclaration ?: return null
    val fqn = validatorFqnFor(decl)
    return if (decl.containingFile != null) {
        if (hasConstraints(decl)) fqn else null
    } else {
        resolver.getClassDeclarationByName(resolver.getKSNameFromString(fqn))?.let { fqn }
    }
}

/** 可逐元素校验的集合类型（请求体是 `List<Dto>` 这类） */
internal val VALIDATABLE_COLLECTIONS = setOf(
    "kotlin.collections.List", "kotlin.collections.Set", "kotlin.collections.Collection",
    "kotlin.collections.Iterable", "kotlin.Array"
)
