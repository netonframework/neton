plugins { alias(libs.plugins.kotlin.multiplatform) }
repositories { mavenCentral() }
val fixture = providers.gradleProperty("compat.case").getOrElse("stable")
require(fixture in setOf("stable", "opt-in", "internal", "http-internal", "ai", "websocket", "global-identity"))
kotlin {
    macosArm64()
    linuxX64()
    mingwX64()
    sourceSets.commonMain {
        kotlin.setSrcDirs(listOf("cases/$fixture"))
        dependencies {
            implementation(project(":neton-core"))
            implementation(project(":neton-http"))
            implementation(project(":neton-database"))
            implementation(project(":neton-ai"))
            implementation(project(":neton-websocket"))
        }
    }
}
