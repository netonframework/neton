plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

repositories { mavenCentral() }

kotlin {
    macosArm64(); macosX64(); linuxX64(); linuxArm64(); mingwX64()
    sourceSets {
        commonMain.dependencies {
            api(project(":neton-core"))
            api(project(":neton-http"))
            api(project(":neton-routing"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(project(":neton-logging"))
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        val nativeMain by creating {
            dependsOn(commonMain.get())
            dependencies { implementation("com.netonstream:websocket:0.2.0") }
        }
        val nativeTest by creating {
            dependsOn(commonTest.get())
            dependencies {
                implementation("com.netonstream:websocket:0.2.0")
                implementation("com.netonstream:tls:0.2.0")
                implementation("com.netonstream:openssl:4.0.2")
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

evaluationDependsOn(":neton")

val verifyWebSocketBoundaries by tasks.registering {
    group = "verification"
    description = "Ensures core, HTTP and the umbrella do not acquire the optional WebSocket module."
    doLast {
        for (module in listOf(":neton-core", ":neton-http", ":neton")) {
            val forbidden = project(module).configurations.flatMap { it.dependencies }
                .filter { it.name == "neton-websocket" || (it.group == "com.netonstream" && it.name == "websocket") }
            check(forbidden.isEmpty()) { "$module must not depend on WebSocket: $forbidden" }
        }
    }
}
tasks.named("check") { dependsOn(verifyWebSocketBoundaries) }
