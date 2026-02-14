package neton.core.config

/**
 * 配置 I/O 平台抽象（Neton-Core-Spec 5.1）。
 * 用于真实加载 .conf 文件与读取环境变量（ENV/CLI 覆盖）。
 *
 * 实现代码：仅 platform 层（POSIX/env）在 macosArm64Main 中，commonMain 仅 expect 声明。
 */
/**
 * 读取路径对应文件内容为 UTF-8 字符串；文件不存在或不可读时返回 null。
 */
expect fun readConfigFile(path: String): String?

/**
 * 返回当前进程环境变量 Map（key -> value），用于 ENV 覆盖。
 */
expect fun getEnvMap(): Map<String, String>

/**
 * 返回当前进程 ID，用于启动 banner 等。
 */
expect fun getProcessId(): Int

/**
 * 读取单个环境变量，用于 banner 的 Environment 等。
 */
expect fun getEnv(key: String): String?
