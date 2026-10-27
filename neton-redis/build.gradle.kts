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
        commonMain {
            dependencies {
                implementation(project(":neton-core"))
                implementation(project(":neton-logging"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                // 后端仅支持 re.this（Kotlin Multiplatform Redis）
                implementation(libs.rethis)
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

tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    val required = providers.environmentVariable("NETON_TEST_REDIS_REQUIRED").orNull == "true"
    environment("NETON_TEST_REDIS_REQUIRED", required.toString())
    providers.environmentVariable("NETON_TEST_REDIS_PORT").orNull?.let {
        environment("NETON_TEST_REDIS_PORT", it)
    }
    inputs.property("redisGateRequired", required)
    outputs.upToDateWhen { !required }
    outputs.doNotCacheIf("Real Redis contracts must execute against this run's server") { required }
}
