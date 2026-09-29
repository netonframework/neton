plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// The engine's libraries (com.netonstream:io / http / tls) are SNAPSHOTs in mavenLocal; only they
// resolve from there (see neton-http-netonstream/README.md, "Build wiring").
repositories {
    exclusiveContent {
        forRepository { mavenLocal() }
        filter { includeModuleByRegex("com[.]netonstream", "(io|http|tls)(-(linux|macos|mingw|ios|android).*)?") }
    }
    mavenCentral()
}

kotlin {
    listOf(macosArm64(), macosX64(), linuxX64(), linuxArm64(), mingwX64()).forEach { target ->
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
                implementation(project(":neton-logging"))
                implementation(project(":neton-routing"))
                implementation(project(":neton-http"))
                implementation(project(":neton-http-netonstream"))
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}

tasks.matching { it.name.startsWith("link") && it.name.contains("Executable") }.configureEach {
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
