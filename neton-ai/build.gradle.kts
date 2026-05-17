plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

repositories {
    mavenCentral()
}

kotlin {
    macosArm64()
    macosX64()
    linuxX64()
    linuxArm64()
    mingwX64()

    // neton-core embeds a C interop (posixenv / neton_get_environ) via a static library.
    // When linking test binaries that depend on neton-core, the static lib must be on the
    // linker search path. Pattern follows examples/helloworld/build.gradle.kts.
    listOf(macosArm64(), macosX64(), linuxX64(), linuxArm64()).forEach { target ->
        val targetName = target.name
        val coreInterop = project(":neton-core").file("build/nativeInterop/$targetName").absolutePath
        target.binaries.configureEach {
            linkerOpts.add("-L$coreInterop")
            linkerOpts.add("-lenv")
        }
    }

    sourceSets {
        val nativeMain by creating {
            dependsOn(commonMain.get())
        }
        val posixMain by creating {
            dependsOn(nativeMain)
        }
        val macosMain by creating {
            dependsOn(posixMain)
        }
        val linuxMain by creating {
            dependsOn(posixMain)
        }
        val macosArm64Main by getting { dependsOn(macosMain) }
        val macosX64Main by getting { dependsOn(macosMain) }
        val linuxX64Main by getting { dependsOn(linuxMain) }
        val linuxArm64Main by getting { dependsOn(linuxMain) }
        val mingwX64Main by getting { dependsOn(nativeMain) }

        commonMain {
            dependencies {
                implementation(project(":neton-core"))
                implementation(project(":neton-logging"))
                implementation(project(":neton-http-client"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.ktor.client.mock)
            }
        }
    }
}

// Ensure neton-core's static env lib is built before linking any test binary.
tasks.matching { it.name.startsWith("link") }.configureEach {
    val targetCapital = when {
        name.contains("MacosArm64") -> "MacosArm64"
        name.contains("MacosX64") -> "MacosX64"
        name.contains("LinuxX64") -> "LinuxX64"
        name.contains("LinuxArm64") -> "LinuxArm64"
        else -> return@configureEach
    }
    dependsOn(":neton-core:archivePosixEnv$targetCapital")
}
