plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

repositories {
    mavenCentral()
}

kotlin {
    macosArm64 {
        binaries {
            executable {
                entryPoint = "main"
            }
        }
    }
    
    sourceSets {
        commonMain {
            dependencies {
                // 基础依赖
            }
        }
    }
}

/** 当前 macOS SDK 里的 Swift 库目录；非 Apple 主机返回 null。 */
val appleSwiftLibDir: String? by lazy {
    if (!org.gradle.internal.os.OperatingSystem.current().isMacOsX) return@lazy null
    runCatching {
        providers.exec {
            commandLine("xcrun", "--sdk", "macosx", "--show-sdk-path")
        }.standardOutput.asText.get().trim()
    }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { "$it/usr/lib/swift" }
}

// ---------- Maven Central 发布（仅库模块，排除示例） ----------
val netonVersion: String by project
val netonGroup: String by project

subprojects {
    if (path.startsWith(":examples")) return@subprojects

    group = netonGroup
    version = netonVersion

    // Apple 目标链接 Swift 互操作库所需的搜索路径。
    //
    // cryptography-kotlin 在 Apple 上把 AES-GCM 解析到 CryptoKit，其实现是 Swift；链接时需要
    // SDK 里的 Swift 运行时存根（libswift_Builtin_float 等）。Kotlin/Native 不会把该目录传给 ld，
    // Xcode 15.x 上会以 "Could not find or use auto-linked library 'swift_*'" 链接失败。
    // CommonCrypto 没有 GCM，换 provider 行不通，因此在链接期补上 SDK 的 swift 目录。
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        val swiftLibDir = appleSwiftLibDir
        if (swiftLibDir != null) {
            extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
                targets.withType(org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget::class.java).configureEach {
                    if (konanTarget.family.isAppleFamily) {
                        binaries.all { linkerOpts("-L$swiftLibDir") }
                    }
                }
            }
        }
    }

    apply(plugin = "maven-publish")
    apply(plugin = "signing")

    afterEvaluate {
        val sub = this@subprojects
        val publishing = sub.extensions.findByType<org.gradle.api.publish.PublishingExtension>() ?: return@afterEvaluate
        // JVM-only 模块（如 neton-ksp）需手动创建 publication
        if (sub.plugins.hasPlugin("org.jetbrains.kotlin.jvm") && publishing.publications.isEmpty()) {
            val javadocJar = sub.tasks.register<Jar>("javadocJar") { archiveClassifier.set("javadoc") }
            val javaExt = sub.extensions.findByType<org.gradle.api.plugins.JavaPluginExtension>()
            val sourcesJar = sub.tasks.register<Jar>("sourcesJar") {
                archiveClassifier.set("sources")
                from(javaExt?.sourceSets?.getByName("main")?.allSource ?: sub.file("src/main"))
            }
            publishing.publications.create<MavenPublication>("maven") {
                from(sub.components["java"])
                artifactId = sub.name
                groupId = netonGroup
                version = netonVersion
                artifact(sourcesJar.get())
                artifact(javadocJar.get())
                pom { configurePom(sub, this) }
            }
        }

        // java-platform（neton-bom）没有自动 publication，手动建
        if (sub.plugins.hasPlugin("java-platform") && publishing.publications.isEmpty()) {
            publishing.publications.create<MavenPublication>("javaPlatform") {
                from(sub.components["javaPlatform"])
                artifactId = sub.name
                groupId = netonGroup
                version = netonVersion
                pom { configurePom(sub, this) }
            }
        }

        // 只统一 group / version / POM，不动 artifactId：KMP 每个 target 的 publication
        // 有自己的 artifactId（neton-core-macosarm64 等），全部改成 sub.name 会让它们互相覆盖，
        // 根坐标上留下的是某个 target 的 .module，消费方按元数据解析就拿到坏包。
        publishing.publications.withType<MavenPublication>().configureEach {
            groupId = netonGroup
            version = netonVersion
            pom { configurePom(sub, this) }
        }

        publishing.repositories {
            maven {
                name = "sonatypeCentral"
                url = uri("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")
                credentials {
                    username = sub.findProperty("sonatypeUsername") as String? ?: ""
                    password = sub.findProperty("sonatypePassword") as String? ?: ""
                }
            }
        }

        if (sub.hasProperty("signing.keyId")) {
            sub.extensions.getByType<org.gradle.plugins.signing.SigningExtension>().sign(publishing.publications)
        }
    }
}

fun configurePom(proj: org.gradle.api.Project, pom: org.gradle.api.publish.maven.MavenPom) {
    pom.name.set(proj.name)
    pom.description.set("Neton Framework - ${proj.name}")
    pom.url.set("https://github.com/netonframework/neton")
    pom.licenses {
        license {
            name.set("Apache-2.0")
            url.set("https://opensource.org/licenses/Apache-2.0")
        }
    }
    pom.developers {
        developer {
            name.set("Netonstream")
            organization.set("Netonstream")
            organizationUrl.set("https://netonstream.com")
        }
    }
    pom.scm {
        url.set("https://github.com/netonframework/neton")
        connection.set("scm:git:git://github.com/netonframework/neton.git")
        developerConnection.set("scm:git:ssh://git@github.com/netonframework/neton.git")
    }
} 