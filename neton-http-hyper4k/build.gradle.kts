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
        val nativeMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                api(project(":neton-http"))
                implementation(project(":neton-core"))
                implementation(project(":neton-logging"))
                implementation("com.netonframework:hyper4k:0.1.0")
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        val nativeTest by creating {
            dependsOn(commonTest.get())
            dependencies {
                implementation(kotlin("test"))
            }
        }

        macosArm64Main.get().dependsOn(nativeMain)
        macosX64Main.get().dependsOn(nativeMain)
        linuxX64Main.get().dependsOn(nativeMain)
        linuxArm64Main.get().dependsOn(nativeMain)
        mingwX64Main.get().dependsOn(nativeMain)
        macosArm64Test.get().dependsOn(nativeTest)
        macosX64Test.get().dependsOn(nativeTest)
        linuxX64Test.get().dependsOn(nativeTest)
        linuxArm64Test.get().dependsOn(nativeTest)
        mingwX64Test.get().dependsOn(nativeTest)
    }
}
