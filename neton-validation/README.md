# neton-validation

Neton 内建校验模块，仅服务 Neton，不对外做通用库。

- **用户只写注解**（`@NotBlank`、`@Min`、`@Email` 等），不 import Konform，不直接使用 `ValidatorRegistry`。
- **KSP** 扫描带校验注解的 data class / 方法参数，生成 `Validator` 实现与 `GeneratedValidatorRegistry`。
- **集成点**：使用 body DTO 时请添加 `implementation(project(":neton-validation"))`。若使用了校验注解（@NotBlank 等），KSP 会生成 `GeneratedValidatorRegistry`，在应用初始化时调用：
  ```kotlin
  neton.validation.generated.GeneratedValidatorRegistry.bindTo(ctx)
  ```
  未绑定时 handler 内 `getOrNull(ValidatorRegistry::class)` 为 null，**会打一行 WARN 日志**（避免校验静默失效难以排查），不 NPE，仅不执行校验。
- **HTTP 层**：生成的 handler 中在反序列化 body 后调用 `ctx.get(ValidatorRegistry::class).get(Dto::class)?.validate(dto)`，若有错误则抛出 `ValidationException(errors)`，由路由层统一 400 + `ErrorResponse`。

错误模型使用 neton-core 的 `ValidationError`（path, message, code）与 `ValidationException`，与现有统一错误输出一致。
