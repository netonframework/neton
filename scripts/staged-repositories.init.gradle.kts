// Explicit release verification only. Never install as a global Gradle init script.
val staged = System.getenv("RELEASE_REPOSITORIES")?.split(",")
    ?: error("RELEASE_REPOSITORIES must list the staging repositories")
gradle.beforeProject {
    repositories {
        staged.forEach { directory -> maven { url = uri(directory) } }
    }
}
