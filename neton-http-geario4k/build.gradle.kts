import org.gradle.api.tasks.PathSensitivity
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

group = "com.netonstream"
version = project.version
repositories { mavenCentral() }

val rustTriples = mapOf(
    "macosArm64" to "aarch64-apple-darwin",
    "macosX64" to "x86_64-apple-darwin",
    "linuxX64" to "x86_64-unknown-linux-gnu",
    "linuxArm64" to "aarch64-unknown-linux-gnu",
)
val ffiDir = rootProject.file("../geario-http/geario-http-ffi")
val ffiHeader = ffiDir.resolve("include")
// Cargo keeps the target directory at the workspace root, not in the member crate.
val ffiTarget = ffiDir.parentFile.resolve("target")

kotlin {
    macosArm64()
    macosX64()
    linuxX64()
    linuxArm64()

    sourceSets {
        val nativeMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                api(project(":neton-http"))
                api(project(":neton-core"))
                implementation(project(":neton-logging"))
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val nativeTest by creating {
            dependsOn(commonTest.get())
            dependencies { implementation(kotlin("test")) }
        }
        macosArm64Main.get().dependsOn(nativeMain)
        macosX64Main.get().dependsOn(nativeMain)
        linuxX64Main.get().dependsOn(nativeMain)
        linuxArm64Main.get().dependsOn(nativeMain)
        macosArm64Test.get().dependsOn(nativeTest)
        macosX64Test.get().dependsOn(nativeTest)
        linuxX64Test.get().dependsOn(nativeTest)
        linuxArm64Test.get().dependsOn(nativeTest)
    }

    targets.withType<KotlinNativeTarget>().configureEach {
        val targetName = name
        val triple = rustTriples[targetName] ?: return@configureEach
        val libDir = ffiTarget.resolve(triple).resolve("release")
        val interop = compilations.getByName("main").cinterops.create("geario_http") {
            defFile(project.file("src/nativeInterop/cinterop/geario_http.def"))
            includeDirs(ffiHeader)
            extraOpts("-libraryPath", libDir.absolutePath)
        }
        tasks.named(interop.interopProcessingTaskName).configure {
            dependsOn("cargoBuild${targetName.replaceFirstChar { it.uppercase() }}")
            inputs.file(libDir.resolve("libgeario_http_ffi.a")).withPathSensitivity(PathSensitivity.NONE)
        }
        binaries.all {
            if (konanTarget.family == org.jetbrains.kotlin.konan.target.Family.LINUX) {
                linkerOpts("-lpthread", "-ldl", "-lm")
            }
        }
    }
}

rustTriples.forEach { (targetName, triple) ->
    tasks.register<Exec>("cargoBuild${targetName.replaceFirstChar { it.uppercase() }}") {
        workingDir(ffiDir)
        commandLine(
            "cargo", "rustc", "--release", "--locked", "--no-default-features",
            "--features", "native-h1", "--target", triple, "--crate-type", "staticlib",
        )
    }
}
