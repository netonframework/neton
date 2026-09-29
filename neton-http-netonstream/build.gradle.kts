plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

/*
 * Published dependencies resolve from Maven Central by default. An explicit staging repository
 * can be used for release verification, never an implicit mavenLocal fallback.
 */
repositories {
    providers.gradleProperty("netonstream.repository").orNull?.let { staged ->
      exclusiveContent {
        forRepository {
                val dir = rootProject.file(staged)
                require(dir.isDirectory) { "netonstream artifact repository does not exist: $dir" }
                maven { url = dir.toURI() }
        }
        filter { includeModuleByRegex("com[.]netonstream", "(io|http|tls)(-(linux|macos|mingw|ios|android).*)?") }
      }
    }
    mavenCentral()
}

val netonstreamIo = "com.netonstream:io:0.1.0"
val netonstreamHttp = "com.netonstream:http:0.1.0"
val netonstreamTls = "com.netonstream:tls:0.1.0"

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
                implementation(netonstreamIo)
                implementation(netonstreamHttp)
                implementation(netonstreamTls)
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
        // The socket-level tests (conformance streaming, h1/h2c/TLS clients) run on macOS and Linux.
        val posixTest by creating {
            dependsOn(nativeTest)
        }

        macosArm64Main.get().dependsOn(nativeMain)
        macosX64Main.get().dependsOn(nativeMain)
        linuxX64Main.get().dependsOn(nativeMain)
        linuxArm64Main.get().dependsOn(nativeMain)
        mingwX64Main.get().dependsOn(nativeMain)
        macosArm64Test.get().dependsOn(posixTest)
        macosX64Test.get().dependsOn(posixTest)
        linuxX64Test.get().dependsOn(posixTest)
        linuxArm64Test.get().dependsOn(posixTest)
        mingwX64Test.get().dependsOn(nativeTest)
    }
}

/*
 * Package rule: the framework (neton-http and friends) and com.netonstream:http both use the
 * package `neton.http`. That is allowed only while no top-level declaration of the framework in
 * `neton.http` has the same fully qualified name as one in the library: two classes with one FQ
 * name would be a link-time clash for every application that has both on its classpath.
 *
 * The check reads the declarations from source: the framework's own `src/<set>/kotlin` trees, and
 * the library's published sources jar. It is part of `check`.
 */
val netonstreamSources: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    netonstreamSources("$netonstreamHttp:sources@jar")
}

val checkNetonHttpPackageClash by tasks.registering {
    group = "verification"
    description = "Fails if the framework and com.netonstream:http declare the same top-level name in package neton.http."
    val sources = netonstreamSources
    val frameworkDirs = rootProject.subprojects
        .filter { !it.path.startsWith(":examples") }
        .map { it.file("src") }
        .filter { it.isDirectory }
    inputs.files(sources)
    inputs.files(frameworkDirs.map { fileTree(it) { include("**/*.kt") } })
    val report = layout.buildDirectory.file("reports/neton-http-package-clash.txt")
    outputs.file(report)
    doLast {
        val declaration = Regex(
            "^(?:(?:public|internal|private|protected|expect|actual|inline|suspend|data|sealed|abstract|open|" +
                "enum|annotation|value|fun|operator|infix|tailrec|external|const|lateinit)\\s+)*" +
                "(class|interface|object|fun|val|var|typealias)\\s+(?:<[^>]*>\\s*)?([A-Za-z_][A-Za-z0-9_.]*)",
        )

        fun topLevelNames(text: String): Set<String> {
            val pkg = Regex("^package\\s+([A-Za-z0-9_.]+)", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)
            if (pkg != "neton.http") return emptySet()
            val names = mutableSetOf<String>()
            for (line in text.lineSequence()) {
                // Top level only: declarations start in column 0.
                if (line.isEmpty() || line[0].isWhitespace()) continue
                if (line.startsWith("private ")) continue
                val m = declaration.find(line) ?: continue
                var name = m.groupValues[2]
                // Extension function or property: `fun A<T>.B.name(` declares `name`.
                val kind = m.groupValues[1]
                if (kind == "fun") {
                    name = Regex("([A-Za-z_][A-Za-z0-9_]*)\\s*\\(").find(line, m.range.first)?.groupValues?.get(1) ?: name
                } else if (kind == "val" || kind == "var") {
                    val head = line.substring(m.range.first).substringBefore('=').substringBefore(':').trim()
                    name = head.substringAfterLast('.').substringAfterLast(' ')
                }
                names += "neton.http.$name"
            }
            return names
        }

        val library = mutableMapOf<String, String>()
        for (jar in sources.files) {
            zipTree(jar).matching { include("**/*.kt") }.forEach { f ->
                for (n in topLevelNames(f.readText())) library.putIfAbsent(n, "${jar.name}!${f.name}")
            }
        }
        check(library.isNotEmpty()) { "no neton.http declarations found in ${sources.files}; the check would pass vacuously" }

        val framework = mutableMapOf<String, String>()
        for (dir in frameworkDirs) {
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                for (n in topLevelNames(f.readText())) framework.putIfAbsent(n, f.relativeTo(rootProject.projectDir).path)
            }
        }

        val clashes = (library.keys intersect framework.keys).sorted()
        val out = report.get().asFile
        out.parentFile.mkdirs()
        out.writeText(
            buildString {
                appendLine("library neton.http top-level declarations: ${library.size} ${library.keys.sorted()}")
                appendLine("framework neton.http top-level declarations: ${framework.size} ${framework.keys.sorted()}")
                appendLine("clashes: ${clashes.size}")
                for (c in clashes) appendLine("  $c: ${framework[c]} vs ${library[c]}")
            },
        )
        if (clashes.isNotEmpty()) {
            throw GradleException(
                "Package rule violated: these fully qualified names exist in both the framework and " +
                    "com.netonstream:http (package neton.http):\n" +
                    clashes.joinToString("\n") { "  $it  (${framework[it]} vs ${library[it]})" },
            )
        }
        logger.lifecycle(
            "neton.http package rule: ${framework.size} framework and ${library.size} library top-level names, no clash",
        )
    }
}

tasks.named("check") { dependsOn(checkNetonHttpPackageClash) }
