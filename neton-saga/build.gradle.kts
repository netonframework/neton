plugins { alias(libs.plugins.kotlin.multiplatform) }

repositories { mavenCentral() }

kotlin {
    macosArm64()
    linuxX64()
    linuxArm64()
    mingwX64()
    sourceSets.commonMain.dependencies { api(project(":neton-database")) }
}
