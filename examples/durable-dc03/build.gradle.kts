plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

repositories { mavenCentral() }

kotlin {
    listOf(macosArm64(), linuxX64(), linuxArm64()).forEach { target ->
        target.binaries.executable {
            entryPoint = "main"
            linkerOpts("-L${project(":neton-core").file("build/nativeInterop/${target.name}").absolutePath}", "-lenv")
            linkTaskProvider.configure { dependsOn(":neton-core:archivePosixEnv${target.name.replaceFirstChar { it.uppercase() }}") }
        }
    }
    sourceSets.commonMain.dependencies {
        implementation(project(":neton-core"))
        implementation(project(":neton-routing"))
        implementation(project(":neton-http"))
        implementation(project(":neton-database"))
        implementation(project(":neton-durable"))
        implementation(project(":neton-saga"))
        implementation(libs.kotlinx.serialization.json)
        implementation(libs.kotlinx.coroutines.core)
    }
}
