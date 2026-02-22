package neton.logging.internal

/**
 * 在 POSIX 平台创建目录（mode 固定 0755）。
 * 使用 expect/actual 避免在共享代码中使用 platform 相关位宽的数字类型（如 mode_t）。
 */
internal expect fun createDirWithMode(path: String)
