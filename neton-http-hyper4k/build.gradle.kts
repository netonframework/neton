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

    sourceSets {
        val nativeMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                api(project(":neton-http"))
                api(project(":neton-core"))
                implementation(project(":neton-logging"))
                // The Rust engine stays its own repository; settings.gradle.kts wires it
                // in with includeBuild when it is checked out next to this one.
                implementation("com.netonframework:hyper4k:0.2.0")
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
        // The conformance streaming checks talk BSD sockets, so they live on the
        // Apple targets rather than in nativeTest, which the Linux targets share.
        val appleTest by creating {
            dependsOn(nativeTest)
        }

        macosArm64Main.get().dependsOn(nativeMain)
        macosX64Main.get().dependsOn(nativeMain)
        linuxX64Main.get().dependsOn(nativeMain)
        linuxArm64Main.get().dependsOn(nativeMain)
        macosArm64Test.get().dependsOn(appleTest)
        macosX64Test.get().dependsOn(appleTest)
        linuxX64Test.get().dependsOn(nativeTest)
        linuxArm64Test.get().dependsOn(nativeTest)
    }
}
