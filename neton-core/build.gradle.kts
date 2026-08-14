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

data class NativeTools(val compiler: String, val archiver: String)

fun configuredTool(property: String, environment: String, default: String): String =
    providers.gradleProperty(property)
        .orElse(providers.environmentVariable(environment))
        .getOrElse(default)

fun kotlinNativeMingwTool(name: String): String? {
    val dependencies = File(System.getProperty("user.home"), ".konan/dependencies")
    return dependencies.listFiles()
        ?.asSequence()
        ?.filter { it.isDirectory && it.name.startsWith("msys2-mingw-w64-x86_64-") }
        ?.map { File(it, "bin/$name.exe") }
        ?.filter { it.isFile }
        ?.maxByOrNull { it.lastModified() }
        ?.absolutePath
}

fun targetTools(targetName: String): NativeTools? = when {
    isMacOs && targetName == "LinuxX64" -> NativeTools(
        compiler = configuredTool("neton.linuxX64.cc", "NETON_LINUX_X64_CC", "x86_64-linux-gnu-gcc"),
        archiver = configuredTool("neton.linuxX64.ar", "NETON_LINUX_X64_AR", "x86_64-linux-gnu-ar"),
    )
    isMacOs && targetName == "LinuxArm64" -> NativeTools(
        compiler = configuredTool("neton.linuxArm64.cc", "NETON_LINUX_ARM64_CC", "aarch64-linux-gnu-gcc"),
        archiver = configuredTool("neton.linuxArm64.ar", "NETON_LINUX_ARM64_AR", "aarch64-linux-gnu-ar"),
    )
    isWindows && targetName == "MingwX64" -> NativeTools(
        compiler = providers.gradleProperty("neton.mingwX64.cc")
            .orElse(providers.environmentVariable("NETON_MINGW_X64_CC"))
            .orNull ?: kotlinNativeMingwTool("gcc") ?: "gcc",
        archiver = providers.gradleProperty("neton.mingwX64.ar")
            .orElse(providers.environmentVariable("NETON_MINGW_X64_AR"))
            .orNull ?: kotlinNativeMingwTool("ar") ?: "ar",
    )
    else -> null
}

fun resolveCommand(command: String): String? {
    val executable = File(command)
    if (executable.isAbsolute || command.contains('/')) {
        return executable.takeIf { it.canExecute() }?.absolutePath
    }
    val names = if (isWindows && executable.extension.isEmpty()) {
        val extensions = System.getenv("PATHEXT")
            ?.split(';')
            ?.filter { it.isNotBlank() }
            ?: listOf(".COM", ".EXE", ".BAT", ".CMD")
        listOf(command) + extensions.map { command + it.lowercase() } + extensions.map { command + it.uppercase() }
    } else {
        listOf(command)
    }
    val searchPaths = buildList {
        System.getenv("PATH")?.split(File.pathSeparatorChar)?.let(::addAll)
        add("/opt/homebrew/bin")
        add("/usr/local/bin")
    }
    return searchPaths.asSequence()
        .flatMap { path -> names.asSequence().map { File(path, it) } }
        .firstOrNull { it.canExecute() }
        ?.absolutePath
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

    val includePath = project.file("src/nativeInterop/c").invariantSeparatorsPath

    // 辅助函数：为每个 Native 目标配置 cinterop 和 linker
    fun org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget.configurePosixEnv() {
        val targetName = this.name
        val targetCapital = targetName.replaceFirstChar { it.uppercase() }
        val interopDir = project.file("build/nativeInterop/$targetName").invariantSeparatorsPath

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

// 为每个目标注册 C bridge 编译 + 归档任务
for (target in nativeTargets) {
    val targetLower = target.name.replaceFirstChar { it.lowercase() }
    val outDir = "build/nativeInterop/$targetLower"

    tasks.register<Exec>("compilePosixEnv${target.name}") {
        val out = file(outDir)
        inputs.files("src/nativeInterop/c/env.c", "src/nativeInterop/c/env.h")
        outputs.file("$outDir/env.o")
        onlyIf { canBuildTarget(target.name) }
        if (target.name == "MingwX64") {
            dependsOn("downloadKotlinNativeDistribution")
        }
        doFirst {
            val tools = targetTools(target.name)
            val configuredCompiler = tools?.compiler ?: "clang"
            val compiler = resolveCommand(configuredCompiler) ?: configuredCompiler
            check(resolveCommand(configuredCompiler) != null) {
                "Missing compiler '$configuredCompiler' for ${target.name}. See the cross-compilation section in README.md."
            }
            out.mkdirs()
            commandLine(
                if (tools != null)
                    listOf(compiler, "-c", "src/nativeInterop/c/env.c", "-I", "src/nativeInterop/c", "-o", "$outDir/env.o")
                else
                    listOf(compiler, "-target", target.clangTarget, "-c", "src/nativeInterop/c/env.c", "-I", "src/nativeInterop/c", "-o", "$outDir/env.o")
            )
        }
    }

    tasks.register<Exec>("archivePosixEnv${target.name}") {
        dependsOn("compilePosixEnv${target.name}")
        outputs.file("$outDir/libenv.a")
        onlyIf { canBuildTarget(target.name) }
        doFirst {
            val tools = targetTools(target.name)
            val configuredArchiver = tools?.archiver ?: "ar"
            val archiver = resolveCommand(configuredArchiver) ?: configuredArchiver
            check(resolveCommand(configuredArchiver) != null) {
                "Missing archiver '$configuredArchiver' for ${target.name}. See the cross-compilation section in README.md."
            }
            file(outDir).mkdirs()
            commandLine(archiver, "rcs", "$outDir/libenv.a", "$outDir/env.o")
        }
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
