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
                implementation(project(":neton-database"))
                implementation(project(":neton-logging"))
                implementation(project(":neton-validation"))
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}

// KSP: 仅用 macosArm64 生成，输出共享到 commonMain
dependencies {
    add("kspMacosArm64", project(":neton-ksp"))
}

val kspOut = file("build/generated/ksp/macosArm64/macosArm64Main/kotlin")
kotlin.sourceSets.named("commonMain") {
    kotlin.srcDir(kspOut)
}

afterEvaluate {
    val ss = kotlin.sourceSets.findByName("macosArm64Main")
    if (ss != null) {
        val filtered = ss.kotlin.srcDirs.filter { !it.path.contains("generated/ksp") }
        if (filtered.size < ss.kotlin.srcDirs.size) ss.kotlin.setSrcDirs(filtered)
    }
}

tasks.matching { it.name.matches(Regex("compileKotlin(MacosArm64|MacosX64|LinuxX64|LinuxArm64|MingwX64)")) }.configureEach {
    dependsOn("kspKotlinMacosArm64")
}

tasks.matching { it.name.startsWith("linkDebugExecutable") }.configureEach {
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
