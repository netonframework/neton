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
        val nativeMain by creating { dependsOn(commonMain.get()) }
        val macosArm64Main by getting { dependsOn(nativeMain) }
        val macosX64Main by getting { dependsOn(nativeMain) }
        val linuxX64Main by getting { dependsOn(nativeMain) }
        val linuxArm64Main by getting { dependsOn(nativeMain) }
        val mingwX64Main by getting { dependsOn(nativeMain) }
        val commonMain by getting {
            dependencies {
                implementation(project(":neton-core"))
                implementation(project(":neton-logging"))

                // Kotlin协程 Native版本
                implementation(libs.kotlinx.coroutines.core)

                // One OpenSSL runtime for HTTP/TLS and application cryptography.
                implementation("com.netonstream:openssl:4.0.2-3")
                // JWT payload JSON 解析
                implementation(libs.kotlinx.serialization.json)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                // Regression: Security and the real HTTP/TLS dependency graph must link together.
                implementation(project(":neton-http"))
            }
        }
    }
}
