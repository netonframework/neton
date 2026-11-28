package neton.ksp

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

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
}
