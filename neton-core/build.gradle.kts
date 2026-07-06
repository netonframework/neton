plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

repositories {
    mavenCentral()
}

// 平台目标与 clang 编译参数映射
data class NativeTarget(val name: String, val clangTarget: String)

val nativeTargets = listOf(
    NativeTarget("MacosArm64", "arm64-apple-macosx"),
    NativeTarget("MacosX64", "x86_64-apple-macosx"),
    NativeTarget("LinuxX64", "x86_64-linux-gnu"),
    NativeTarget("LinuxArm64", "aarch64-linux-gnu"),
    NativeTarget("MingwX64", "x86_64-w64-mingw32"),
)

val hostOs = System.getProperty("os.name").lowercase()
val isMacOs = hostOs.contains("mac")
val isLinux = hostOs.contains("linux")
val isWindows = hostOs.contains("windows")

// Linux 目标的 C 桥交叉编译器：macOS 上用 homebrew macos-cross-toolchains 的 <arch>-linux-gnu-gcc
// （带 Linux sysroot，env.c 只需 <signal.h>+environ）；Linux 原生用 clang -target。
fun crossGccFor(targetName: String): String? = when (targetName) {
    "LinuxX64" -> "x86_64-linux-gnu-gcc"
    "LinuxArm64" -> "aarch64-linux-gnu-gcc"
    else -> null
}

// macOS 上用交叉 gcc 即可为 Linux 目标交叉编译（恢复 2026-07-02 aeda47b 前的 macOS 交叉编译能力）；
// 交叉 gcc 缺失时 compilePosixEnv 任务会以明确错误失败（而非静默产错架构）。
fun canBuildTarget(targetName: String): Boolean = when (targetName) {
    "MacosArm64", "MacosX64" -> isMacOs
    "LinuxX64", "LinuxArm64" -> isLinux || isMacOs
    "MingwX64" -> isWindows
    else -> false
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    val includePath = project.file("src/nativeInterop/c").absolutePath

    // 辅助函数：为每个 Native 目标配置 cinterop 和 linker
    fun org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget.configurePosixEnv() {
        val targetName = this.name
        val targetCapital = targetName.replaceFirstChar { it.uppercase() }
        val interopDir = project.file("build/nativeInterop/$targetName").absolutePath

        val defFile = layout.buildDirectory.file("posixenv-$targetName.def").get().asFile
        tasks.register("writePosixenvDef$targetCapital") {
            dependsOn("archivePosixEnv$targetCapital")
            outputs.file(defFile)
            doLast {
                check(canBuildTarget(targetCapital)) {
                    "$targetCapital must be built on its native host because the C bridge requires platform system headers"
                }
                defFile.parentFile.mkdirs()
                defFile.writeText("""
                    language = C
                    package = neton.env
                    headers = env.h
                    includeDirs = $includePath
                    staticLibraries = libenv.a
                    libraryPaths = $interopDir
                """.trimIndent())
            }
        }

        compilations.getByName("main").cinterops {
            create("posixenv") {
                defFile(defFile)
                compilerOpts.add("-I$includePath")
            }
        }
    }

    macosArm64 { configurePosixEnv() }
    macosX64 { configurePosixEnv() }
    linuxX64 { configurePosixEnv() }
    linuxArm64 { configurePosixEnv() }
    mingwX64 { configurePosixEnv() }

    sourceSets {
        // 源集层级：commonMain → nativeMain → posixMain → macOS/Linux 目标
        //                                  → mingwX64Main
        val nativeMain by creating {
            dependsOn(commonMain.get())
        }
        val posixMain by creating {
            dependsOn(nativeMain)
        }
        val macosArm64Main by getting { dependsOn(posixMain) }
        val macosX64Main by getting { dependsOn(posixMain) }
        val linuxX64Main by getting { dependsOn(posixMain) }
        val linuxArm64Main by getting { dependsOn(posixMain) }
        val mingwX64Main by getting { dependsOn(nativeMain) }

        commonMain {
            dependencies {
                implementation(project(":neton-logging"))
                implementation(libs.kotlin.stdlib.common)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.server.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.ktor.server.sessions)
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

// KSP 配置
dependencies {
    add("kspCommonMainMetadata", project(":neton-ksp"))
}

// 确保生成的代码包含在编译中
kotlin.sourceSets.commonMain {
    kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
}

// 为每个目标注册 clang 编译 + ar 归档任务
for (target in nativeTargets) {
    val targetLower = target.name.replaceFirstChar { it.lowercase() }
    val outDir = "build/nativeInterop/$targetLower"

    tasks.register<Exec>("compilePosixEnv${target.name}") {
        val out = file(outDir)
        inputs.files("src/nativeInterop/c/env.c", "src/nativeInterop/c/env.h")
        outputs.file("$outDir/env.o")
        onlyIf { canBuildTarget(target.name) }
        // macOS 交叉编 Linux 目标时，clang -target 找不到 Linux 系统头（signal.h），改用带
        // sysroot 的交叉 gcc（<arch>-linux-gnu-gcc）；原生/其它目标仍用 clang -target。
        val crossGcc = crossGccFor(target.name)
        val cmd = if (isMacOs && crossGcc != null)
            listOf(crossGcc, "-c", "src/nativeInterop/c/env.c", "-I", "src/nativeInterop/c", "-o", "$outDir/env.o")
        else
            listOf("clang", "-target", target.clangTarget, "-c", "src/nativeInterop/c/env.c", "-I", "src/nativeInterop/c", "-o", "$outDir/env.o")
        commandLine(cmd)
        doFirst { out.mkdirs() }
    }

    tasks.register<Exec>("archivePosixEnv${target.name}") {
        dependsOn("compilePosixEnv${target.name}")
        outputs.file("$outDir/libenv.a")
        onlyIf { canBuildTarget(target.name) }
        commandLine("ar", "rcs", "$outDir/libenv.a", "$outDir/env.o")
        doFirst { file(outDir).mkdirs() }
    }
}

// cinterop 任务依赖对应平台的 writePosixenvDef
tasks.matching { it.name.contains("cinterop") && it.name.contains("Posixenv") }.configureEach {
    inputs.files("src/nativeInterop/c/env.h")
    for (target in nativeTargets) {
        if (name.contains(target.name)) {
            dependsOn("writePosixenvDef${target.name}")
        }
    }
}

// link 任务依赖对应平台的 archivePosixEnv
tasks.matching {
    it.name.contains("link") && nativeTargets.any { t -> name.contains(t.name) }
}.configureEach {
    for (target in nativeTargets) {
        if (name.contains(target.name)) {
            dependsOn("archivePosixEnv${target.name}")
        }
    }
}
