# neton-logging

Neton 地基模块：**唯一 Logger API**、结构化日志、Trace/Request Context、**@Log 注入**。单模块内 API/impl 分层。

- **规范**：[Neton-Logging-Spec-v1.md](../../neton-docs/Neton-Logging-Spec-v1.md)（v1 设计冻结）
- **本模块**：API 在 `neton.logging`（Logger、LoggerFactory、LogContext、Fields、SensitiveFilter、LogLevel、defaultLoggerFactory）；实现在 `neton.logging.internal`（internal，禁止其他模块直接依赖）。
- **业务层**：使用 `@Log` + 构造参数 `log: Logger`，由 KSP 在 Controller/Service 实例化时注入；**禁止**直接调用 `LoggerFactory.get()`。
- **约束**：其他模块只能依赖 `neton.logging` 公共 API，禁止 import `neton.logging.internal`。

## Multi-Sink（Phase 1 + Phase 2）

- **配置**：仅从 `application.conf` 的 `[logging]` + `[[logging.sinks]]` 读取；v1.1 无 `logging.conf`。
- **stdout 规则**：有 sinks 配置时默认关闭（避免双写）；无配置时默认开启；需 stdout 时显式加 `name=stdout` sink。
- **Phase 1**：`[logging.async]` 缺省或 `enabled=false` → 同步写入。
- **Phase 2**：`[logging.async] enabled=true` → 异步队列，debug/info 可丢，warn/error 不丢（队列满时同步 fallback），见 spec 十一 B。
