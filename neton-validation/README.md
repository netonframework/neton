# neton-validation

Neton 内建校验模块，仅服务 Neton，不对外做通用库。

- **用户只写注解**（`@NotBlank`、`@Size`、`@Min`、`@Email`、`@Valid` 等），标在请求体 DTO 的属性上
  （`@property:Size(...)` 或主构造里 `val` 参数上的 `@Size(...)` 都可以）。
- **KSP** 为每个带约束的类型生成一个校验器：与 DTO 同包的 `<Name>NetonValidator`（嵌套类型
  `Outer.Inner` → `Outer_InnerNetonValidator`），可见性跟随 DTO。
- **HTTP 层**：生成的路由 handler 在反序列化 body 之后**直接调用**这个校验器，有错误就抛
  `ValidationException(errors)`（400，`VALIDATION_FAILED`），handler 不会执行。
  不经过运行期注册表，所以不存在「查不到校验器就跳过」的路径：校验器没生成时编译失败。
  DTO 来自依赖模块时，按确定名字引用该模块生成的校验器——DTO 所在模块必须跑 neton-ksp。
- **写了必须生效**：约束注解标在 handler 方法参数、普通函数参数、非 `val` 构造参数、私有类型上，
  或类型不匹配（`@Size` 用在非字符串/集合上、`@Valid` 指向没有约束的类型）时，编译失败。
- 业务代码需要手动校验时直接调用 `XxxNetonValidator.validate(value)`。

错误模型使用 neton-core 的 `ValidationError`（field/path, code, message=code, params）与 `ValidationException`。
