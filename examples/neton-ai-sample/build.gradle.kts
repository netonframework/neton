plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

repositories {
    mavenCentral()
}

kotlin {
    listOf(macosArm64(), macosX64(), linuxX64(), linuxArm64(), mingwX64()).forEach { target ->
        target.binaries {
            executable {
                entryPoint = "neton.ai.sample.main"
            }
        }
    }

    sourceSets {
        val nativeMain by creating { dependsOn(commonMain.get()) }
        val posixMain by creating { dependsOn(nativeMain) }
        val macosMain by creating { dependsOn(posixMain) }
        val linuxMain by creating { dependsOn(posixMain) }
        val macosArm64Main by getting { dependsOn(macosMain) }
        val macosX64Main by getting { dependsOn(macosMain) }
        val linuxX64Main by getting { dependsOn(linuxMain) }
        val linuxArm64Main by getting { dependsOn(linuxMain) }
        val mingwX64Main by getting { dependsOn(nativeMain) }

        commonMain {
            dependencies {
                implementation(project(":neton-http"))
                implementation(project(":neton-http-hyper4k"))
                implementation(project(":neton-ai"))
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}
