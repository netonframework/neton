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
        val nativeMain = create("nativeMain") {
            dependsOn(commonMain.get())
        }
        val posixMain = create("posixMain") {
            dependsOn(nativeMain)
        }
        val macosArm64Main = getByName("macosArm64Main") { dependsOn(posixMain) }
        val macosX64Main = getByName("macosX64Main") { dependsOn(posixMain) }
        val linuxX64Main = getByName("linuxX64Main") { dependsOn(posixMain) }
        val linuxArm64Main = getByName("linuxArm64Main") { dependsOn(posixMain) }
        val mingwX64Main = getByName("mingwX64Main") { dependsOn(nativeMain) }

        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib.common)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}
