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
                implementation(project(":neton-redis"))
                implementation(project(":neton-validation"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }
    }
}

// KSP 按编译目标配置
dependencies {
    add("kspMacosArm64", project(":neton-ksp"))
    add("kspMacosX64", project(":neton-ksp"))
    add("kspLinuxX64", project(":neton-ksp"))
    add("kspLinuxArm64", project(":neton-ksp"))
    add("kspMingwX64", project(":neton-ksp"))
}

// KSP 生成代码加入各平台 sourceSet
for (targetName in listOf("MacosArm64", "MacosX64", "LinuxX64", "LinuxArm64", "MingwX64")) {
    val lower = targetName.replaceFirstChar { it.lowercase() }
    kotlin.sourceSets.named("${lower}Main") {
        kotlin.srcDir("build/generated/ksp/$lower/${lower}Main/kotlin")
    }
}

// compile 依赖 KSP 生成
tasks.matching { it.name.matches(Regex("compileKotlin(MacosArm64|MacosX64|LinuxX64|LinuxArm64|MingwX64)")) }.configureEach {
    val targetName = name.removePrefix("compileKotlin")
    dependsOn("kspKotlin$targetName")
}

// link 依赖 libenv.a
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
