import java.security.MessageDigest
import java.util.Base64

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

repositories { mavenCentral() }

val hostTarget = when {
    System.getProperty("os.name").contains("Mac") -> "macosArm64"
    System.getProperty("os.name").contains("Windows") -> "mingwX64"
    System.getProperty("os.arch") == "aarch64" -> "linuxArm64"
    else -> "linuxX64"
}
val hostCapital = hostTarget.replaceFirstChar { it.uppercase() }
val migrationOutput = layout.buildDirectory.dir("generated/migrations/kotlin")
val generateMigrationResources by tasks.registering {
    val sql = layout.projectDirectory.file("sql/postgresql/V001__reference.sql")
    inputs.file(sql)
    outputs.dir(migrationOutput)
    doLast {
        val bytes = sql.asFile.readBytes()
        val encoded = Base64.getEncoder().encodeToString(bytes)
        val checksum = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val output = migrationOutput.get().file("init/generated/ReferenceMigrationResources.kt").asFile
        output.parentFile.mkdirs()
        output.writeText("""
            package init.generated
            import kotlin.io.encoding.Base64
            import neton.core.module.*
            object ReferenceMigrationResources {
                val sources = listOf(MigrationSource("reference", MigrationDialect.POSTGRESQL, listOf(
                    MigrationScript("001", "reference", Base64.decode("$encoded").decodeToString(), "$checksum")
                )))
            }
        """.trimIndent())
    }
}

kotlin {
    listOf(macosArm64(), linuxX64(), linuxArm64(), mingwX64()).forEach { target ->
        target.binaries.executable { entryPoint = "main" }
        target.binaries.forEach { binary ->
            binary.linkerOpts("-L${project(":neton-core").file("build/nativeInterop/${target.name}").absolutePath}", "-lenv")
            binary.linkTaskProvider.configure {
                dependsOn(":neton-core:archivePosixEnv${target.name.replaceFirstChar { it.uppercase() }}")
            }
        }
    }
    sourceSets.commonMain {
        kotlin.srcDir(migrationOutput)
        kotlin.srcDir("build/generated/ksp/$hostTarget/${hostTarget}Main/kotlin")
        dependencies {
            implementation(project(":neton-core"))
            implementation(project(":neton-http"))
            implementation(project(":neton-routing"))
            implementation(project(":neton-database"))
            implementation(project(":neton-logging"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
dependencies { add("ksp$hostCapital", project(":neton-ksp")) }
ksp { arg("neton.moduleId", "reference") }
tasks.matching { it.name.startsWith("kspKotlin") }.configureEach { dependsOn(generateMigrationResources) }
tasks.matching { it.name.startsWith("compileKotlin") || it.name == "compileCommonMainKotlinMetadata" }.configureEach {
    dependsOn("kspKotlin$hostCapital")
}
afterEvaluate {
    kotlin.sourceSets.findByName("${hostTarget}Main")?.let { source ->
        source.kotlin.setSrcDirs(source.kotlin.srcDirs.filterNot { it.invariantSeparatorsPath.contains("generated/ksp") })
    }
}
