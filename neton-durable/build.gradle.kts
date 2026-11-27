plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

repositories { mavenCentral() }

kotlin {
    macosArm64()
    macosX64()
    linuxX64()
    linuxArm64()
    mingwX64()
    sourceSets.commonMain.dependencies {
        api(project(":neton-database"))
        implementation(libs.kotlinx.serialization.json)
    }
}
