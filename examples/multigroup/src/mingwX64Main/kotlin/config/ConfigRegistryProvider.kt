package config

import neton.core.config.NetonConfigRegistry
import neton.core.generated.GeneratedNetonConfigRegistry

actual fun defaultConfigRegistry(): NetonConfigRegistry? = GeneratedNetonConfigRegistry
