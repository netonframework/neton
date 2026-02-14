plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

kotlin {
    macosArm64()
    macosX64()
    linuxX64()
    linuxArm64()
    mingwX64()

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":neton-core"))
                implementation(project(":neton-logging"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.sqlx4k.sqlite)
                // PostgreSQL/MySQL：按需添加，在 database.conf 中配置 driver/uri 即可
                implementation(libs.sqlx4k.postgres)
                implementation(libs.sqlx4k.mysql)
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}
