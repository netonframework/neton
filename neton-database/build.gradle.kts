plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// NETON-DB-VARIANT (2026-05-20):
//   K/N executable 只能链接一个 sqlx4k native driver (每个 driver klib 含完整 Rust runtime;
//   多个 driver 同时链接 → ld.lld duplicate symbol). 通过 gradle property `neton.database.driver`
//   选 postgres / mysql / sqlite (默认 postgres). 只该 driver 的 lib + 对应的 srcDir 被加入编译.
//
//   切换 variant 示例:
//     ./gradlew -Pneton.database.driver=sqlite <test target>
val nDbDriver: String = (project.findProperty("neton.database.driver") as? String)
    ?.lowercase()
    ?: "postgres"

require(nDbDriver in listOf("postgres", "mysql", "sqlite")) {
    "Unsupported -Pneton.database.driver=$nDbDriver; must be one of: postgres, mysql, sqlite"
}

val nDbDriverLib = when (nDbDriver) {
    "postgres" -> libs.sqlx4k.postgres
    "mysql" -> libs.sqlx4k.mysql
    "sqlite" -> libs.sqlx4k.sqlite
    else -> error("unreachable")
}

kotlin {
    macosArm64()
    linuxX64()
    linuxArm64()
    mingwX64()

    sourceSets {
        commonMain {
            kotlin.srcDirs("src/commonMain/kotlin", "src/${nDbDriver}Variant/kotlin")
            dependencies {
                implementation(project(":neton-core"))
                implementation(project(":neton-logging"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)
                // 唯一 sqlx4k driver — 由 -Pneton.database.driver 决定 (默认 postgres).
                implementation(nDbDriverLib)
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
