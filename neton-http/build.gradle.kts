plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

repositories {
    mavenCentral()
}

kotlin {
    macosArm64()
    macosX64()
    linuxX64()
    linuxArm64()
    mingwX64()

    sourceSets {
        val nativeMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation("com.netonframework:hyper4k:0.1.0")
            }
        }
        val posixMain by creating {
            dependsOn(nativeMain)
        }
        // Per-target source sets —— ktor client engines 不能共用 posixMain
        // （macOS 走 Darwin, Linux 走 CIO, mingw 走 WinHttp）。
        val macosArm64Main by getting {
            dependsOn(posixMain)
            dependencies { implementation(libs.ktor.client.darwin) }
        }
        val macosX64Main by getting {
            dependsOn(posixMain)
            dependencies { implementation(libs.ktor.client.darwin) }
        }
        val linuxX64Main by getting {
            dependsOn(posixMain)
            dependencies { implementation(libs.ktor.client.cio) }
        }
        val linuxArm64Main by getting {
            dependsOn(posixMain)
            dependencies { implementation(libs.ktor.client.cio) }
        }
        val mingwX64Main by getting {
            dependsOn(nativeMain)
            dependencies { implementation(libs.ktor.client.winhttp) }
        }

        commonMain {
            dependencies {
                implementation(project(":neton-core"))
                implementation(project(":neton-logging"))
                implementation(libs.ktor.io)
                implementation(libs.ktor.http.cio)
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.server.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.ktor.server.sessions)
                implementation(libs.ktor.server.cors)
                implementation(libs.kotlinx.coroutines.core)
                // ktor client (出站) —— framework-level HTTP client factory
                // 在 commonMain 暴露 newNetonHttpClient(); 各 native target 在
                // 自己的 source set 里挂 platform engine（Darwin / CIO / WinHttp）。
                api(libs.ktor.client.core)
                api(libs.ktor.client.content.negotiation)
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":neton-core"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }
    }
}
