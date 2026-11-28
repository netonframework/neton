package neton.core.module

/** Build metadata emitted by Neton KSP on a generated module entry. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class ModuleArchitecture(
    val id: String,
    val dependencies: Array<String>,
    val origin: String,
)
