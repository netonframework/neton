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
        }
        val posixMain by creating {
            dependsOn(nativeMain)
        }
        val macosMain by creating {
            dependsOn(posixMain)
            dependencies { implementation(libs.ktor.client.darwin) }
        }
        val linuxMain by creating {
            dependsOn(posixMain)
            dependencies { implementation(libs.ktor.client.cio) }
        }
        val macosArm64Main by getting {
            dependsOn(macosMain)
        }
        val macosX64Main by getting {
            dependsOn(macosMain)
        }
        val linuxX64Main by getting {
            dependsOn(linuxMain)
        }
        val linuxArm64Main by getting {
            dependsOn(linuxMain)
        }
        val mingwX64Main by getting {
            dependsOn(nativeMain)
            dependencies { implementation(libs.ktor.client.winhttp) }
        }

        commonMain {
            dependencies {
                api(project(":neton-core"))
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
                // Public outbound client SPI exposes Ktor's engine factory for test injection.
                api(libs.ktor.client.core)
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(project(":neton-core"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.mock)
            }
        }
    }
}
