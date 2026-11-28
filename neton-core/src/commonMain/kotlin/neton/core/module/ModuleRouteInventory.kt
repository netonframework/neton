package neton.core.module

/** Compile-time route declarations on a generated module entry, before runtime mounting. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class ModuleRouteInventory(val routes: Array<ModuleRouteDeclaration>)

@Target()
@Retention(AnnotationRetention.BINARY)
annotation class ModuleRouteDeclaration(
    val method: String,
    val pattern: String,
    val group: String,
    val permissions: Array<String>,
    val allowAnonymous: Boolean,
    val requireAuth: Boolean,
    val freshAuth: Boolean,
    val capabilities: Array<String>,
    val origin: String,
)
