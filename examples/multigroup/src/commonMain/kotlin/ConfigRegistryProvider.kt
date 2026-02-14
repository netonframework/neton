package config

import neton.core.config.NetonConfigRegistry

/**
 * 由各平台 actual 提供 KSP 生成的 Registry；KSP 当前仅输出到 macosArm64Main，故 actual 暂留 platform 层。
 */
expect fun defaultConfigRegistry(): NetonConfigRegistry?
