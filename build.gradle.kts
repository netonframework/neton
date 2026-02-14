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