plugins {
    // 不指定版本，复用根项目 Kotlin Multiplatform 已加载的 Kotlin 插件，避免版本冲突
    id("org.jetbrains.kotlin.jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.devtools.ksp:symbol-processing-api:2.3.5")
    
    implementation("org.jetbrains.kotlin:kotlin-stdlib")

    // KSP 处理器不再依赖 Core 模块
    // 因为 Core 模块是 Kotlin Native 专用，而 KSP 运行在 JVM 上
    // KSP 处理器通过注解名称字符串来识别注解，不需要直接依赖

    testImplementation(kotlin("test"))
    // 在 JVM 上真正跑一遍 kotlinc + KSP，验证诊断能否阻断编译（KSP 处理器本就只能跑在 JVM 上）
    testImplementation(libs.kctfork.core)
    testImplementation(libs.kctfork.ksp)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

// 确保生成的源码包含在编译中
kotlin {
    sourceSets.main {
        kotlin.srcDir("build/generated/ksp/main/kotlin")
    }
} 