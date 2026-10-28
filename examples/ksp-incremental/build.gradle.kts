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
    sourceSets.commonMain {
        providers.gradleProperty("gate.sources").orNull?.let { kotlin.srcDir(it) }
        kotlin.srcDir("build/generated/ksp/$gateTarget/${gateTarget}Main/kotlin")
        dependencies {
            implementation(project(":neton-core"))
            implementation(project(":neton-database"))
        }
    }
}
dependencies { add("ksp$capital", project(":neton-ksp")) }
tasks.matching { it.name.startsWith("compileKotlin") }.configureEach { dependsOn("kspKotlin$capital") }
afterEvaluate {
    kotlin.sourceSets.findByName("${gateTarget}Main")?.let { source ->
        source.kotlin.setSrcDirs(source.kotlin.srcDirs.filterNot { it.path.contains("generated/ksp") })
    }
}
