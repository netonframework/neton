plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

repositories {
    mavenCentral()
}

kotlin {
    val nativeTargets = listOf(macosArm64(), macosX64(), linuxX64(), linuxArm64(), mingwX64())

    nativeTargets.forEach { target ->
        target.binaries {
            executable {
                entryPoint = "neton.migrate.main"
                baseName = "neton-migrate"
            }
        }
        val targetName = target.name
        val coreInterop = project(":neton-core").file("build/nativeInterop/$targetName").absolutePath
        target.binaries.forEach { binary ->
            binary.linkerOpts.add("-L$coreInterop")
            binary.linkerOpts.add("-lenv")
        }
    }

    // posixMain / mingwX64Main 共享配置
    val posixTargetNames = listOf("macosArm64Main", "macosX64Main", "linuxX64Main", "linuxArm64Main")
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":neton-core"))
                implementation(project(":neton-logging"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.sqlx4k.sqlite)
                implementation(libs.sqlx4k.postgres)
                implementation(libs.sqlx4k.mysql)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val posixMain by creating {
            dependsOn(commonMain)
        }
        posixTargetNames.forEach { name ->
            getByName(name).dependsOn(posixMain)
        }
    }
}

// 链接 neton-core 的 posix env interop（与 examples 一致）
tasks.matching {
    it.name.startsWith("linkDebugExecutable") || it.name.startsWith("linkReleaseExecutable")
}.configureEach {
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
