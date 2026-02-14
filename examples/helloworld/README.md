# Neton HelloWorld 示例

极简示例：无 KSP、无 Controller，仅用 routing DSL。

## 运行

```bash
./gradlew :examples:helloworld:linkDebugExecutableMacosArm64
./examples/helloworld/build/bin/macosArm64/debugExecutable/helloworld.kexe
```

框架会根据 `config/application.conf` 的 `[logging] level` 自动设置 Ktor 内部日志级别，无需手动指定 `KTOR_LOG_LEVEL`。

访问 http://localhost:8080/ 返回 `Hello Neton!`
