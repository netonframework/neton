# Neton 基准应用（Hyper4k 默认引擎）

TechEmpower 风格的框架级基准应用。无参 `http { }` 解析到 Hyper4k；
对照 Ktor 时把它换成 `http(::KtorHttpAdapter)`，其余路由与响应契约不变。
完整规则见 `neton-docs` 的《HTTP 引擎能力规范》第九节。

## 构建与运行

```bash
# 在 neton 仓库根目录
./gradlew :examples:bench:linkReleaseExecutableMacosArm64

./examples/bench/build/bin/macosArm64/releaseExecutable/bench.kexe
```

## 压测

```bash
ab -k -c 256 -n 100000 http://127.0.0.1:8090/plaintext
ab -k -c 256 -n 100000 http://127.0.0.1:8090/json
```
