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
        val macosMain = create("macosMain") {
            dependsOn(posixMain)
        }
        val linuxMain = create("linuxMain") {
            dependsOn(posixMain)
        }
        val macosArm64Main = getByName("macosArm64Main") { dependsOn(macosMain) }
        val macosX64Main = getByName("macosX64Main") { dependsOn(macosMain) }
        val linuxX64Main = getByName("linuxX64Main") { dependsOn(linuxMain) }
        val linuxArm64Main = getByName("linuxArm64Main") { dependsOn(linuxMain) }
        val mingwX64Main = getByName("mingwX64Main") { dependsOn(nativeMain) }

        commonMain {
            dependencies {
                implementation(project(":neton-core"))
                implementation(project(":neton-logging"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation("com.netonstream:openssl:4.1.0-1")
                implementation(project(":neton-http"))
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
