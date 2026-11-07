# Neton 基准应用（内置 HTTP 引擎）

TechEmpower 风格的框架级基准应用。无参 `http { }` 使用 Neton 内置 HTTP 引擎。
注意：当前 `/plaintext` 的字符串返回值经过统一响应包装，实际返回 JSON；
该工作负载不等价于 TechEmpower 纯文本测试，不能直接对比其他框架的纯文本成绩。
完整规则见 `neton-docs` 的《HTTP 引擎能力规范》第九节。

## 构建与运行

```bash
# 在 neton 仓库根目录
./gradlew -Pnetonstream.local=false :examples:bench:linkReleaseExecutableMacosArm64

./examples/bench/build/bin/macosArm64/releaseExecutable/bench.kexe
```

## 压测

```bash
ab -k -c 256 -n 100000 http://127.0.0.1:8090/plaintext
ab -k -c 256 -n 100000 http://127.0.0.1:8090/json
```

上述命令仅为本机基准预检，不代表生产性能验收。保留完整输出，检查失败请求、
非 2xx 响应、吞吐及 p99；先做预热，再至少重复五轮。记录提交号、依赖基线、
编译器、二进制 SHA-256、硬件、并发数及后台负载。固定硬件交错执行前后版本，
再根据测量波动确定回退阈值。负载发生器与服务共用一台机器时必须明确标记。

## 稳定性预检

在仓库根目录构建测试后，以同一个进程重复全部用例：

```bash
./gradlew -Pnetonstream.local=false :neton-websocket:macosArm64Test
python3 -B tools/ci/repeat_transport_tests.py \
  --binary neton-websocket/build/bin/macosArm64/debugTest/test.kexe \
  --iterations 100 --timeout 1800 --output /tmp/neton-repeat-unique
```

输出目录必须不存在。工具记录每轮测试完整性、原始日志、耗时和二进制摘要；
失败或超时保留失败证据。提交号只标识当前工作树，不证明传入二进制的构建来源。
重复测试不等同于持续连接长稳，也不测量内存泄漏、句柄增长或分配量。
正式长稳仍需专用环境运行至少 24 小时，覆盖慢客户端、重连、故障注入与反复关闭，
采集内存、句柄和关闭后资源；本工具不能将其标记为已通过。
