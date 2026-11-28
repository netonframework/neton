plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.ksp)
}

repositories { mavenCentral() }

val gateTarget = providers.gradleProperty("gate.target").getOrElse("macosArm64")
val capital = gateTarget.replaceFirstChar { it.uppercase() }
kotlin {
    val target = when (gateTarget) {
        "macosArm64" -> macosArm64()
        "linuxX64" -> linuxX64()
        "linuxArm64" -> linuxArm64()
        "mingwX64" -> mingwX64()
        else -> error("Unsupported database fixture target: $gateTarget")
    }
    target.binaries.executable { entryPoint = "fixture.main" }
    target.binaries.forEach { binary ->
        binary.linkerOpts("-L${project(":neton-core").file("build/nativeInterop/$gateTarget").absolutePath}", "-lenv")
        binary.linkTaskProvider.configure { dependsOn(":neton-core:archivePosixEnv$capital") }
    }
    target.compilations.getByName("main").defaultSourceSet.kotlin.apply {
        srcDir("src/nativeApp/kotlin")
        providers.gradleProperty("gate.sources").orNull?.let { srcDir(it) }
    }
    sourceSets.commonMain {
        dependencies {
            implementation(project(":neton-core"))
            implementation(project(":neton-database"))
            implementation(project(":examples:ksp-incremental:billing"))
        }
    }
}
dependencies { add("ksp$capital", project(":neton-ksp")) }
ksp { arg("neton.modules", "billing,catalog") }
