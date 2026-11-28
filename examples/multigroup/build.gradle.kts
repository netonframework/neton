plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

repositories {
    mavenCentral()
}

kotlin {
    listOf(macosArm64(), macosX64(), linuxX64(), linuxArm64(), mingwX64()).forEach { target ->
        target.compilations.getByName("main").defaultSourceSet.kotlin.srcDir("src/nativeApp/kotlin")
        project.dependencies.add(
            "ksp${target.name.replaceFirstChar { it.uppercase() }}",
            project.dependencies.project(mapOf("path" to ":neton-ksp")),
        )
        target.binaries {
            executable {
                entryPoint = "main"
            }
        }
        val targetName = target.name
        val coreInterop = project(":neton-core").file("build/nativeInterop/$targetName").absolutePath
        target.binaries.forEach { binary ->
            binary.linkerOpts.add("-L$coreInterop")
            binary.linkerOpts.add("-lenv")
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":neton-core"))
                implementation(project(":neton-routing"))
                implementation(project(":neton-security"))
                implementation(project(":neton-http"))
                implementation(project(":neton-redis"))
                implementation(project(":neton-validation"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

// link 依赖 libenv.a
tasks.matching { it.name.startsWith("linkDebugExecutable") || it.name.startsWith("linkDebugTest") }.configureEach {
    val targetName = when {
        name.contains("MacosArm64") -> "MacosArm64"
        name.contains("MacosX64") -> "MacosX64"
        name.contains("LinuxX64") -> "LinuxX64"
        name.contains("LinuxArm64") -> "LinuxArm64"
        name.contains("MingwX64") -> "MingwX64"
        else -> return@configureEach
    }
    dependsOn(":neton-core:archivePosixEnv$targetName")
}
