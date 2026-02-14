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
        val commonMain by getting {
            dependencies {
                implementation(project(":neton-core"))
                implementation(project(":neton-logging"))

                // Kotlin协程 Native版本
                implementation(libs.kotlinx.coroutines.core)

                // cryptography-kotlin (HS256 for JWT, Native via CommonCrypto/OpenSSL)
                implementation(libs.cryptography.core)
                implementation(libs.cryptography.provider.optimal)
                // JWT payload JSON 解析
                implementation(libs.kotlinx.serialization.json)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
