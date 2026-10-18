package neton.ksp.validation

/**
 * KSP 内部：单个属性的校验规则
 */
data class ValidationRule(
    val propertyName: String,
    val annotationSimpleName: String,
    /**
     * 注解上作者给的字段机器码（`message = "INVALID_MOBILE"`）；null 表示用约束的内建码
     * （`REQUIRED`/`TOO_SHORT`/…，见 neton-core `ValidationCode`）。
     */
    val customCode: String?,
    val valueArgs: List<String> = emptyList(),
    /** 属性类型 FQN，用于 Size（String→length / List|Set|Map|Array→size） */
    val propertyTypeQualified: String? = null,
    /** 是否可空，用于 @NotBlank：可空时生成 null || isBlank() */
    val isNullable: Boolean = false,
    /** @Valid：嵌套类型（集合时为元素类型）的校验器全限定名 */
    val nestedValidatorFqn: String? = null,
    /** @Valid 标在集合上：逐元素校验，路径 `prop[i].field` */
    val nestedIsCollection: Boolean = false
)

/**
 * KSP 内部：待生成 Validator 的 DTO 模型
 */
data class ValidationModel(
    val qualifiedName: String,
    val simpleName: String,
    val rules: List<ValidationRule>,
    val packageName: String = qualifiedName.substringBeforeLast('.', ""),
    /** 生成的校验器 object 名（与 DTO 同包），见 validatorFqnFor */
    val validatorSimpleName: String = "${simpleName}NetonValidator",
    /** "public" / "internal"：跟随 DTO 的有效可见性 */
    val visibility: String = "public"
)
