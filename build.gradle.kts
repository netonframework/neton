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

// ---------- Maven Central 发布（仅库模块，排除示例） ----------
val netonVersion: String by project
val netonGroup: String by project

subprojects {
    if (path.startsWith(":examples")) return@subprojects

    group = netonGroup
    version = netonVersion

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

        publishing.publications.withType<MavenPublication>().configureEach {
            groupId = netonGroup
            artifactId = sub.name
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