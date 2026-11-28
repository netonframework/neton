package neton.ksp

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.kspSourcesDir
import java.io.File
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalCompilerApi::class)
class ModuleArchitectureDiagnosticsTest {
    private fun compile(options: Map<String, String>, anchor: String = "object Main") =
        KotlinCompilation().apply {
            sources = listOf(
                SourceFile.kotlin("Module.kt", """
                    package neton.core.annotations
                    annotation class Module(val id: String, val dependsOn: Array<String> = [])
                """.trimIndent()),
                SourceFile.kotlin("Anchor.kt", "package fixture\n$anchor"),
                SourceFile.kotlin("Context.kt", "package neton.core.component\ninterface NetonContext"),
                architecture,
                SourceFile.kotlin("Initializer.kt", """
                    package neton.core.module
                    interface ModuleInitializer {
                        val moduleId: String
                        val dependsOn: List<String> get() = emptyList()
                        fun initialize(ctx: neton.core.component.NetonContext)
                    }
                """.trimIndent()),
            )
            inheritClassPath = true
            configureKsp {
                symbolProcessorProviders += ModuleInitializerProcessorProvider()
                symbolProcessorProviders += ApplicationRegistryProcessorProvider()
                processorOptions.putAll(options)
                withCompilation = false
            }
        }.compile()

    @Test fun duplicateSelectedModule() {
        val result = compile(mapOf("neton.modules" to "billing, billing"))
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "NETON_ARCH_DUPLICATE_MODULE: neton.modules includes 'billing'")
    }

    @Test fun selfDependencyAtAnchor() {
        val result = compile(mapOf("neton.moduleId" to "billing"),
            """@neton.core.annotations.Module("billing", ["billing"]) object Billing""")
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "Anchor.kt")
        assertContains(result.messages, "NETON_ARCH_MODULE_CYCLE: billing -> billing")
    }

    @Test fun legacySelfDependency() {
        val result = compile(mapOf("neton.moduleId" to "billing", "neton.moduleDependsOn" to "catalog, billing"))
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "NETON_ARCH_MODULE_CYCLE: billing -> billing")
    }

    @Test fun differentDependencyAccepted() {
        val result = compile(mapOf("neton.moduleId" to "billing"),
            """@neton.core.annotations.Module("billing", ["catalog"]) object Billing""")
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test fun duplicateDependencyAtAnchor() {
        val result = compile(mapOf("neton.moduleId" to "billing"),
            """@neton.core.annotations.Module("billing", ["catalog", "catalog"]) object Billing""")
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "Anchor.kt")
        assertContains(result.messages, "NETON_ARCH_DUPLICATE_DEPENDENCY: billing -> catalog")
    }

    @Test fun duplicateLegacyDependency() {
        val result = compile(mapOf("neton.moduleId" to "billing", "neton.moduleDependsOn" to "catalog,catalog"))
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "NETON_ARCH_DUPLICATE_DEPENDENCY: billing -> catalog")
    }

    private val architecture = SourceFile.kotlin("Architecture.kt", """
        package neton.core.module
        @Retention(AnnotationRetention.BINARY)
        annotation class ModuleArchitecture(val id: String, val dependencies: Array<String>, val origin: String)
    """.trimIndent())

    private fun entry(id: String, vararg deps: String): SourceFile {
        val name = id.replaceFirstChar { it.uppercaseChar() }
        val edges = deps.joinToString(",") { "\"$it\"" }
        return SourceFile.kotlin("$name.kt", """
            package neton.module.$id.generated
            import neton.core.module.*
            @ModuleArchitecture("$id", [$edges], "$name.kt:4")
            object ${name}ModuleManifest : ModuleInitializer
        """.trimIndent())
    }

    private fun assemble(ids: String, modules: KotlinCompilation) =
        KotlinCompilation().apply {
            sources = listOf(SourceFile.kotlin("App.kt", "object App"))
            classpaths = listOf(modules.classesDir)
            inheritClassPath = true
            configureKsp {
                symbolProcessorProviders += ApplicationRegistryProcessorProvider()
                processorOptions["neton.modules"] = ids
                withCompilation = true
            }
        }

    private fun modules(vararg sources: SourceFile) = KotlinCompilation().apply {
        this.sources = listOf(architecture, SourceFile.kotlin("Initializer.kt",
            "package neton.core.module\ninterface ModuleInitializer"), *sources)
        inheritClassPath = true
    }.also {
        val result = it.compile()
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    private fun generated(assembly: KotlinCompilation): File =
        assembly.kspSourcesDir.walkTopDown().single { it.name == "GeneratedApplicationModules.kt" }

    @Test fun cycleInCompiledModuleMetadata() {
        val result = assemble("billing,catalog", modules(entry("billing", "catalog"), entry("catalog", "billing"))).compile()
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "NETON_ARCH_MODULE_CYCLE: billing -> catalog -> billing (Billing.kt:4)")
    }

    @Test fun availableButUnselectedModuleIsMissingFromAssembly() {
        val result = assemble("billing", modules(entry("billing", "catalog"), entry("catalog"))).compile()
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode)
        assertContains(result.messages, "NETON_ARCH_MISSING_DEPENDENCY: billing -> catalog (Billing.kt:4)")
    }

    @Test fun deterministicManifestKeepsUnknownLegacyEdgesExplicit() {
        val compiled = modules(entry("billing", "catalog"), SourceFile.kotlin("Catalog.kt", """
            package init
            object CatalogModuleInitializer : neton.core.module.ModuleInitializer
        """.trimIndent()))
        fun build(): String {
            val assembly = assemble("billing,catalog", compiled)
            val result = assembly.compile()
            assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
            return generated(assembly).readText()
        }
        val first = build()
        assertEquals(first, build())
        assertContains(first, """\"declaredDependencies\":[\"catalog\"]""")
        assertContains(first, """\"declaredDependencies\":null""")
        assertContains(first, "manifestSha256")
        assertFalse(first.contains(compiled.workingDir.absolutePath))
    }
}
