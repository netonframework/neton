plugins {
    `java-platform`
}

// 版本对齐清单：只约束当前发布的 neton-* 库模块。
// 由 :neton 聚合模块通过 api(platform(...)) 导出，也可单独 platform("com.netonstream:neton-bom") 引用。
javaPlatform {
    allowDependencies()
}

dependencies {
    constraints {
        val unreleased = rootProject.extra["neton.unreleasedProjects"] as Set<*>
        rootProject.subprojects
            .filter {
                it.path != project.path && !it.path.startsWith(":examples") &&
                    it.name != "neton" && it.name !in unreleased
            }
            // External compatibility adapters have their own release lifecycle.
            .forEach { api("${project.group}:${it.name}:${project.version}") }
    }
}
