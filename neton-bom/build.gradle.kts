plugins {
    `java-platform`
}

// 版本对齐清单：把全部 neton-* 库模块钉在同一版本。
// 由 :neton 聚合模块通过 api(platform(...)) 导出，也可单独 platform("com.netonstream:neton-bom") 引用。
javaPlatform {
    allowDependencies()
}

dependencies {
    constraints {
        rootProject.subprojects
            .filter { it.path != project.path && !it.path.startsWith(":examples") && it.name != "neton" }
            .forEach { api("${project.group}:${it.name}:${project.version}") }
    }
}
