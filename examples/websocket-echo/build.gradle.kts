plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.ksp)
}
repositories { mavenCentral() }
kotlin {
    listOf(macosArm64(), linuxX64(), linuxArm64()).forEach { target ->
        target.binaries.executable {
            entryPoint = "example.websocket.main"
            linkerOpts("-L${project(":neton-core").file("build/nativeInterop/${target.name}").absolutePath}", "-lenv")
        }
    }
    sourceSets.commonMain.dependencies {
        implementation(project(":neton-websocket"))
        implementation(project(":neton-logging"))
        implementation(libs.kotlinx.coroutines.core)
        implementation(libs.kotlinx.serialization.json)
    }
}
dependencies { add("kspMacosArm64", project(":neton-ksp")) }
val generated = file("build/generated/ksp/macosArm64/macosArm64Main/kotlin")
kotlin.sourceSets.commonMain { kotlin.srcDir(generated) }
afterEvaluate {
    kotlin.sourceSets.named("macosArm64Main") {
        kotlin.setSrcDirs(kotlin.srcDirs.filterNot { it.path.contains("generated/ksp") })
    }
}
tasks.matching { it.name.matches(Regex("compileKotlin(MacosArm64|LinuxX64|LinuxArm64)")) }.configureEach {
    dependsOn("kspKotlinMacosArm64")
}
tasks.matching { it.name.startsWith("link") && it.name.contains("Executable") }.configureEach {
    val target = when {
        name.contains("MacosArm64") -> "MacosArm64"
        name.contains("LinuxArm64") -> "LinuxArm64"
        else -> "LinuxX64"
    }
    dependsOn(":neton-core:archivePosixEnv$target")
}
