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

## Linux HTTP 低负载长稳

`tools/ci/http_soak.py` 使用本应用的 Linux release 二进制，仅访问本机回环地址。
启动前校验二进制 SHA-256、端口和可用内存，启动后核实服务自身只监听指定的
`127.0.0.1` 地址。每分钟约 50 次请求，覆盖连接复用、重连、慢读取、半包关闭和
客户端 RST，随后静默 10 秒采样回收结果。请求正文与状态不匹配立即失败。

运行必须置于独立 systemd 服务：非特权用户、CPUQuota=25%、MemoryHigh=192M、
MemoryMax=256M、MemorySwapMax=0、TasksMax=64、低调度优先级、仅回环网络、
只读系统目录和独立状态目录。不要占用已有服务端口或修改已有服务配置。
完整运行设置 `--seconds 86400 --warmup 600`，外部 RuntimeMaxSec 为 87000，
并保留至少 40 秒停止宽限。先用 `--seconds 180 --warmup 60` 预演。

脚本输出 `evidence.json`、`samples.jsonl` 和 `server.log`，包括候选提交、二进制与
基线摘要、脚本摘要、请求计数、RSS、文件描述符、socket、线程数和宿主可用内存。
正式运行期间不得替换二进制、脚本或调整阈值；失败后修复须从头重新计时。

默认验收边界：预热后每个静默窗口的描述符、socket、线程不得超过基线；RSS
不超过基线加 32 MiB；末段与初段静默 RSS 中位数增量不超过 8 MiB；服务 RSS
达到 192 MiB 或宿主可用内存低于 256 MiB 时停止。最终必须自行响应 SIGTERM、
以 0 退出，且进程资源和监听端口已消失，强制清理不能计为正常关闭通过。

这些是本工作负载的保守回归阈值，不是“绝无内存泄漏”的证明。只有连续完成
至少 24 小时且所有检查成功，`soakCertified` 才为 true；预演、被中断或只有
启动日志的任务都不算通过。该验收仅覆盖 HTTP/1 内置框架链路，不覆盖 TLS、
WebSocket、HTTP/3、QUIC，也不构成性能基准。Maven 发布还需独立的完整发布门禁。
