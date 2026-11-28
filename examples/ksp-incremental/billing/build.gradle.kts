plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.ksp)
}

repositories { mavenCentral() }

val gateTarget = providers.gradleProperty("gate.target").getOrElse("macosArm64")
val capital = gateTarget.replaceFirstChar { it.uppercase() }
kotlin {
    when (gateTarget) {
        "macosArm64" -> macosArm64()
        "linuxX64" -> linuxX64()
        "linuxArm64" -> linuxArm64()
        "mingwX64" -> mingwX64()
        else -> error("Unsupported fixture target: $gateTarget")
    }
    sourceSets.commonMain {
        kotlin.setSrcDirs(listOf(providers.gradleProperty("gate.billingSources").getOrElse("src/commonMain/kotlin")))
        dependencies { implementation(project(":neton-core")) }
    }
}
dependencies { add("ksp$capital", project(":neton-ksp")) }
ksp { arg("neton.moduleId", "billing") }
