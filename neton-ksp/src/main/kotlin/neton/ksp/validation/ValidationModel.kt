package neton.ksp.validation

/**
 * KSP 内部：单个属性的校验规则
 */
data class ValidationRule(
    val propertyName: String,
    val annotationSimpleName: String,
    val message: String,
    val constraintCode: String,
    val valueArgs: List<String> = emptyList(),
    /** 属性类型 FQN，用于 Size（String→length / List|Set|Map|Array→size） */
    val propertyTypeQualified: String? = null,
    /** 是否可空，用于 @NotBlank：可空时生成 null || isBlank() */
    val isNullable: Boolean = false
)

/**
 * KSP 内部：待生成 Validator 的 DTO 模型
 */
data class ValidationModel(
    val qualifiedName: String,
    val simpleName: String,
    val rules: List<ValidationRule>
)
