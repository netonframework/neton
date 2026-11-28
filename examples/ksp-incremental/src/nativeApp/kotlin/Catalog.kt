package neton.module.catalog.generated

import neton.core.component.NetonContext
import neton.core.module.ModuleArchitecture
import neton.core.module.ModuleInitializer

@ModuleArchitecture("catalog", [], "Catalog.kt")
object CatalogModuleManifest : ModuleInitializer {
    override val moduleId = "catalog"
    override fun initialize(ctx: NetonContext) = Unit
}
