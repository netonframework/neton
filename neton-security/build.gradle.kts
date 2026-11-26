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
        val nativeMain = create("nativeMain") { dependsOn(commonMain.get()) }
        val macosArm64Main = getByName("macosArm64Main") { dependsOn(nativeMain) }
        val macosX64Main = getByName("macosX64Main") { dependsOn(nativeMain) }
        val linuxX64Main = getByName("linuxX64Main") { dependsOn(nativeMain) }
        val linuxArm64Main = getByName("linuxArm64Main") { dependsOn(nativeMain) }
        val mingwX64Main = getByName("mingwX64Main") { dependsOn(nativeMain) }
        val commonMain = getByName("commonMain") {
            dependencies {
                implementation(project(":neton-core"))
                implementation(project(":neton-logging"))

                // Kotlin协程 Native版本
                implementation(libs.kotlinx.coroutines.core)

                // One OpenSSL runtime for HTTP/TLS and application cryptography.
                implementation("com.netonstream:openssl:4.1.0-1")
                // JWT payload JSON 解析
                implementation(libs.kotlinx.serialization.json)
            }
        }

        val commonTest = getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                // Regression: Security and the real HTTP/TLS dependency graph must link together.
                implementation(project(":neton-http"))
            }
        }
    }
}
