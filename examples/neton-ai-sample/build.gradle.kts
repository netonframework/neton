plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

repositories {
    mavenCentral()
}

kotlin {
    listOf(macosArm64(), macosX64(), linuxX64(), linuxArm64(), mingwX64()).forEach { target ->
        target.binaries {
            executable {
                entryPoint = "neton.ai.sample.main"
            }
        }
    }

    // Link against neton-core's posixenv library for POSIX targets only
    listOf(macosArm64(), macosX64(), linuxX64(), linuxArm64()).forEach { target ->
        val targetName = target.name
        val coreInterop = project(":neton-core").file("build/nativeInterop/$targetName").absolutePath
        target.binaries.forEach { binary ->
            binary.linkerOpts.add("-L$coreInterop")
            binary.linkerOpts.add("-lenv")
        }
    }

    sourceSets {
        val nativeMain by creating { dependsOn(commonMain.get()) }
        val posixMain by creating { dependsOn(nativeMain) }
        val macosMain by creating { dependsOn(posixMain) }
        val linuxMain by creating { dependsOn(posixMain) }
        val macosArm64Main by getting { dependsOn(macosMain) }
        val macosX64Main by getting { dependsOn(macosMain) }
        val linuxX64Main by getting { dependsOn(linuxMain) }
        val linuxArm64Main by getting { dependsOn(linuxMain) }
        val mingwX64Main by getting { dependsOn(nativeMain) }

        commonMain {
            dependencies {
                implementation(project(":neton-http-client"))
                implementation(project(":neton-ai"))
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}

tasks.matching { it.name.startsWith("linkDebugExecutable") }.configureEach {
    val targetName = when {
        name.contains("MacosArm64") -> "MacosArm64"
        name.contains("MacosX64") -> "MacosX64"
        name.contains("LinuxX64") -> "LinuxX64"
        name.contains("LinuxArm64") -> "LinuxArm64"
        name.contains("MingwX64") -> return@configureEach // mingwX64 doesn't need posixenv
        else -> return@configureEach
    }
    dependsOn(":neton-core:archivePosixEnv$targetName")
}
