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
                api(project(":neton-core"))
                implementation(project(":neton-logging"))
                // The Rust engine stays its own repository; settings.gradle.kts wires it
                // in with includeBuild when it is checked out next to this one.
                implementation("com.netonstream:hyper4k:0.2.0")
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
        // The client conformance suite's origin talks BSD sockets: POSIX targets.
        val posixTest by creating {
            dependsOn(nativeTest)
        }
        // The server-side streaming checks use an Apple-only socket client for now.
        val appleTest by creating {
            dependsOn(posixTest)
        }

        macosArm64Main.get().dependsOn(nativeMain)
        macosX64Main.get().dependsOn(nativeMain)
        linuxX64Main.get().dependsOn(nativeMain)
        linuxArm64Main.get().dependsOn(nativeMain)
        mingwX64Main.get().dependsOn(nativeMain)
        macosArm64Test.get().dependsOn(appleTest)
        macosX64Test.get().dependsOn(appleTest)
        linuxX64Test.get().dependsOn(posixTest)
        linuxArm64Test.get().dependsOn(posixTest)
        mingwX64Test.get().dependsOn(nativeTest)
    }
}
