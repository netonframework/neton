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
        }
        val posixMain by creating {
            dependsOn(nativeMain)
        }
        val macosMain by creating {
            dependsOn(posixMain)
        }
        val linuxMain by creating {
            dependsOn(posixMain)
        }
        val macosArm64Main by getting { dependsOn(macosMain) }
        val macosX64Main by getting { dependsOn(macosMain) }
        val linuxX64Main by getting { dependsOn(linuxMain) }
        val linuxArm64Main by getting { dependsOn(linuxMain) }
        val mingwX64Main by getting { dependsOn(nativeMain) }

        commonMain {
            dependencies {
                implementation(project(":neton-core"))
                implementation(project(":neton-logging"))
                implementation(project(":neton-http"))
                implementation(libs.kotlinx.coroutines.core)
                // 协议层的公开类型（IR、报文）直接暴露 JsonObject
                api(libs.kotlinx.serialization.json)
            }
        }

        commonTest {
            dependencies {
                // No engine: the tests script HttpClient directly (neton-http testkit),
                // which is what keeps neton-ai's test suite alive if an engine is removed.
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

// spec/ai.md §11：依赖单向，neton.ai.protocol 不得引用 neton.ai 的 SDK 类型
val checkProtocolLayering by tasks.registering {
    val dir = file("src/commonMain/kotlin/neton/ai/protocol")
    inputs.dir(dir)
    doLast {
        val bad = dir.walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().filter { it.startsWith("import neton.ai.") && !it.startsWith("import neton.ai.protocol.") }
                .map { "${f.relativeTo(dir)}: $it" }
        }.toList()
        if (bad.isNotEmpty()) throw GradleException("neton.ai.protocol must not depend on the SDK layer:\n" + bad.joinToString("\n"))
    }
}
tasks.named("check") { dependsOn(checkProtocolLayering) }
