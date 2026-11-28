plugins { alias(libs.plugins.kotlin.multiplatform) }

repositories { mavenCentral() }

kotlin {
    val targets = listOf(macosArm64(), linuxX64(), linuxArm64())
    val fixtureMain = sourceSets.create("fixtureMain") {
        dependsOn(sourceSets.commonMain.get())
        dependencies {
            implementation(project(":neton"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
    for (target in targets) {
        target.compilations.getByName("main").defaultSourceSet.dependsOn(fixtureMain)
        target.binaries.executable {
            entryPoint = "main"
            linkerOpts("-L${project(":neton-core").file("build/nativeInterop/${target.name}").absolutePath}", "-lenv")
            linkTaskProvider.configure {
                dependsOn(":neton-core:archivePosixEnv${target.name.replaceFirstChar { it.uppercase() }}")
            }
        }
    }
}
