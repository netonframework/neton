package neton.ksp

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import java.io.OutputStreamWriter

private val MODULE_ID_PATTERN = Regex("^[a-z][a-z0-9-]*$")

/**
 * 模块初始化器聚合处理器
 *
 * 读取 KSP 选项 `neton.moduleId`，从 ModuleFragmentSink 收集各 Processor 写入的片段，
 * 聚合生成模块级初始化器。两种模式：
 *
 * 1. **Manifest 模式（MANIFEST-P1）** — 模块声明了 `@NetonModule` 锚点 object：
 *    生成 `{Id}ModuleManifest : ModuleInitializer`，聚合 fragments + 约定 FQN 探测：
 *    - `init.generated.{Id}MigrationResources` 存在 → override migrations()
 *    - `init.{Id}RuntimeBootstrap` 存在 → 在 logics 之后、routes 之前调用
 *    fragment 按 domain 冻结顺序输出: configs → logics → (bootstrap) → routes → jobs → validators。
 *    注解 id 必须与 ksp arg `neton.moduleId` 一致（编译错误）；dependsOn 以注解为准。
 *
 * 2. **兼容模式** — 无 @NetonModule：维持原行为，生成 `{Id}ModuleInitializer`，
 *    dependsOn 来自 ksp arg `neton.moduleDependsOn`。
 *
 * moduleId 约束：只允许 [a-z][a-z0-9-]*（小写字母开头，后续小写/数字/短横线）。
 *
 * KSP 选项配置（build.gradle.kts）：
 * ```
 * ksp { arg("neton.moduleId", "member") }
 * ```
 *
 * 若未配置 `neton.moduleId`，则不生成任何代码（保持兼容模式）。
 */
class ModuleInitializerProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>
) : SymbolProcessor {

    private var invoked = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (invoked) return emptyList()
        invoked = true

        val moduleId = options["neton.moduleId"]
        if (moduleId.isNullOrBlank()) {
            return emptyList()
        }

        // P0-2: moduleId 格式校验 fail-fast
        if (!MODULE_ID_PATTERN.matches(moduleId)) {
            logger.error(
                "neton.moduleId '$moduleId' is invalid. " +
                        "Must match [a-z][a-z0-9-]* (lowercase, start with letter, allow digits and hyphens)"
            )
            return emptyList()
        }

        if (!ModuleFragmentSink.hasFragments(moduleId)) {
            logger.info("ModuleInitializer[$moduleId]: no fragments collected, skip generation")
            return emptyList()
        }

        // MANIFEST-P1: @NetonModule 锚点探测。存在 → Manifest 模式；否则兼容模式。
        val anchor = findModuleAnchor(resolver, moduleId)

        if (anchor != null) {
            generateModuleManifest(resolver, moduleId, anchor)
        } else {
            generateModuleInitializer(moduleId)
        }

        // 清空该 moduleId 的 sink，避免多轮累积
        ModuleFragmentSink.clear(moduleId)

        return emptyList()
    }

    private data class ModuleAnchor(
        val declaredId: String,
        val dependsOn: List<String>,
    )

    /** 扫 @NetonModule；校验唯一性 + id 与 ksp arg 互证。校验失败返回 null 且已报 error。 */
    private fun findModuleAnchor(resolver: Resolver, moduleId: String): ModuleAnchor? {
        val symbols = resolver
            .getSymbolsWithAnnotation("neton.core.annotations.NetonModule")
            .filterIsInstance<KSClassDeclaration>()
            .toList()
        if (symbols.isEmpty()) return null
        if (symbols.size > 1) {
            logger.error(
                "@NetonModule declared ${symbols.size} times in module '$moduleId' " +
                        "(${symbols.joinToString { it.qualifiedName?.asString() ?: "?" }}). " +
                        "Exactly one anchor object per module."
            )
            return null
        }
        val decl = symbols.first()
        val ann = decl.annotations.first {
            it.annotationType.resolve().declaration.qualifiedName?.asString() ==
                    "neton.core.annotations.NetonModule"
        }
        val declaredId = ann.arguments.firstOrNull { it.name?.asString() == "id" }?.value as? String
        if (declaredId.isNullOrBlank()) {
            logger.error("@NetonModule on ${decl.qualifiedName?.asString()}: id is required.", decl)
            return null
        }
        if (declaredId != moduleId) {
            logger.error(
                "@NetonModule(id = \"$declaredId\") on ${decl.qualifiedName?.asString()} does not " +
                        "match KSP option neton.moduleId='$moduleId'. The annotation is the declared " +
                        "surface, the KSP arg is the shared processor carrier — they must agree.",
                decl
            )
            return null
        }
        @Suppress("UNCHECKED_CAST")
        val deps = (ann.arguments.firstOrNull { it.name?.asString() == "dependsOn" }?.value as? List<String>)
            ?: emptyList()
        return ModuleAnchor(declaredId, deps)
    }

    /** fragment domain 冻结顺序: configs → logics → (bootstrap) → routes → jobs → validators */
    private val domainOrder = mapOf(
        "configs" to 0,
        "logics" to 1,
        // bootstrap 调用插在 1 与 3 之间
        "routes" to 3,
        "jobs" to 4,
        "validators" to 5,
    )

    // ---------- Manifest 模式 (MANIFEST-P1) ----------

    private fun generateModuleManifest(resolver: Resolver, moduleId: String, anchor: ModuleAnchor) {
        val pascalId = moduleId.toPascalCase()
        val className = "${pascalId}ModuleManifest"
        val pkg = "neton.module.$moduleId.generated"

        // 约定 FQN 探测（编译期；存在才生成调用）
        val migrationsFqn = "init.generated.${pascalId}MigrationResources"
        val hasMigrations = resolver.getClassDeclarationByName(
            resolver.getKSNameFromString(migrationsFqn)
        ) != null
        val bootstrapFqn = "init.${pascalId}RuntimeBootstrap"
        val hasBootstrap = resolver.getClassDeclarationByName(
            resolver.getKSNameFromString(bootstrapFqn)
        ) != null
        if (!hasMigrations) {
            logger.warn(
                "@NetonModule[$moduleId]: $migrationsFqn not found — manifest will not override " +
                        "migrations(). If this module has schema, check generateMigrationResources task order."
            )
        }

        val fragments = ModuleFragmentSink.getFragments(moduleId)
            .sortedBy { domainOrder[it.domain] ?: 2 }
        val imports = ModuleFragmentSink.getImports(moduleId)
        val topLevelDecls = ModuleFragmentSink.getTopLevelDeclarations(moduleId)
        val stats = ModuleFragmentSink.getStats(moduleId)

        val file = codeGenerator.createNewFile(
            dependencies = Dependencies(true),
            packageName = pkg,
            fileName = className
        )

        OutputStreamWriter(file).use { w ->
            w.write("// AUTO-GENERATED by Neton KSP ModuleInitializerProcessor (manifest mode) - DO NOT EDIT\n")
            w.write("package $pkg\n\n")

            w.write("import neton.core.component.NetonContext\n")
            w.write("import neton.core.module.ModuleInitializer\n")
            if (hasMigrations) {
                w.write("import neton.core.module.MigrationSource\n")
            }
            imports.sorted().forEach { w.write("$it\n") }
            w.write("\n")

            topLevelDecls.forEach { decl ->
                w.write(decl)
                w.write("\n")
            }

            w.write("/**\n")
            w.write(" * 模块 [$moduleId] 的自动生成 Manifest（@NetonModule, MANIFEST-P1）。\n")
            w.write(" *\n")
            w.write(" * 聚合本模块全部 KSP 产物 + migrations 探测 + 手写 runtime bootstrap。\n")
            w.write(" * 顺序: configs → logics(@Logic) → RuntimeBootstrap → routes → jobs → validators。\n")
            w.write(" * 模块侧薄壳: `object {X}ModuleInitializer : ModuleInitializer by $className`。\n")
            w.write(" */\n")
            w.write("object $className : ModuleInitializer {\n\n")
            w.write("    override val moduleId: String = \"$moduleId\"\n\n")

            if (anchor.dependsOn.isNotEmpty()) {
                val depsList = anchor.dependsOn.joinToString(", ") { "\"$it\"" }
                w.write("    override val dependsOn: List<String> = listOf($depsList)\n\n")
            }

            if (stats.isNotEmpty()) {
                val entries = stats.entries.joinToString(", ") { "\"${it.key}\" to ${it.value}" }
                w.write("    override val stats: Map<String, Int> = mapOf($entries)\n\n")
            }

            if (hasMigrations) {
                w.write("    /** SQL embed 进 binary（gradle generateMigrationResources task 生成）。 */\n")
                w.write("    override fun migrations(): List<MigrationSource> = $migrationsFqn.sources\n\n")
            }

            w.write("    override fun initialize(ctx: NetonContext) {\n")

            var bootstrapWritten = !hasBootstrap
            fragments.forEach { fragment ->
                // bootstrap 调用插在 logics(order<=1) 之后、第一个 order>=3 fragment 之前
                if (!bootstrapWritten && (domainOrder[fragment.domain] ?: 2) >= 3) {
                    w.write("        // 手写 runtime bootstrap（engine/scheduler/watchdog 等复杂装配）\n")
                    w.write("        $bootstrapFqn.initialize(ctx)\n\n")
                    bootstrapWritten = true
                }
                w.write("        // ${fragment.comment}\n")
                w.write(fragment.code)
                w.write("\n\n")
            }
            if (!bootstrapWritten) {
                w.write("        // 手写 runtime bootstrap（engine/scheduler/watchdog 等复杂装配）\n")
                w.write("        $bootstrapFqn.initialize(ctx)\n\n")
            }

            w.write("    }\n")
            w.write("}\n")
        }

        logger.info(
            "Generated $pkg.$className (manifest mode) with ${fragments.size} fragment(s), " +
                    "migrations=$hasMigrations bootstrap=$hasBootstrap"
        )
    }

    // ---------- 兼容模式（无 @NetonModule，维持原行为） ----------

    private fun generateModuleInitializer(moduleId: String) {
        val pascalId = moduleId.toPascalCase()
        val className = "${pascalId}ModuleInitializer"
        val pkg = "neton.module.$moduleId.generated"

        val fragments = ModuleFragmentSink.getFragments(moduleId)
        val imports = ModuleFragmentSink.getImports(moduleId)
        val topLevelDecls = ModuleFragmentSink.getTopLevelDeclarations(moduleId)
        val stats = ModuleFragmentSink.getStats(moduleId)

        val file = codeGenerator.createNewFile(
            dependencies = Dependencies(true),
            packageName = pkg,
            fileName = className
        )

        OutputStreamWriter(file).use { w ->
            w.write("// AUTO-GENERATED by Neton KSP ModuleInitializerProcessor - DO NOT EDIT\n")
            w.write("package $pkg\n\n")

            // imports
            w.write("import neton.core.component.NetonContext\n")
            w.write("import neton.core.module.ModuleInitializer\n")
            imports.sorted().forEach { w.write("$it\n") }
            w.write("\n")

            // 顶层声明（Validator 类等）
            topLevelDecls.forEach { decl ->
                w.write(decl)
                w.write("\n")
            }

            w.write("/**\n")
            w.write(" * 模块 [$moduleId] 的自动生成初始化器\n")
            w.write(" *\n")
            w.write(" * 聚合了本模块的全部 KSP 产物（路由、校验器、仓库、定时任务、配置器）。\n")
            w.write(" * 在 Neton.run { modules($className) } 中注册即可加载。\n")
            w.write(" */\n")
            w.write("object $className : ModuleInitializer {\n\n")
            w.write("    override val moduleId: String = \"$moduleId\"\n\n")

            // dependsOn
            val dependsOnRaw = options["neton.moduleDependsOn"]?.takeIf { it.isNotBlank() }
            if (dependsOnRaw != null) {
                val deps = dependsOnRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                if (deps.isNotEmpty()) {
                    val depsList = deps.joinToString(", ") { "\"$it\"" }
                    w.write("    override val dependsOn: List<String> = listOf($depsList)\n\n")
                }
            }

            if (stats.isNotEmpty()) {
                val entries = stats.entries.joinToString(", ") { "\"${it.key}\" to ${it.value}" }
                w.write("    override val stats: Map<String, Int> = mapOf($entries)\n\n")
            }
            w.write("    override fun initialize(ctx: NetonContext) {\n")

            fragments.forEach { fragment ->
                w.write("        // ${fragment.comment}\n")
                w.write(fragment.code)
                w.write("\n\n")
            }

            w.write("    }\n")
            w.write("}\n")
        }

        logger.info("Generated $pkg.$className with ${fragments.size} fragment(s)")
    }
}

/** 将 kebab-case 或 snake_case 转为 PascalCase */
private fun String.toPascalCase(): String {
    return split('-', '_', '.').joinToString("") { part ->
        part.replaceFirstChar { it.uppercaseChar() }
    }
}

class ModuleInitializerProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return ModuleInitializerProcessor(
            environment.codeGenerator,
            environment.logger,
            environment.options
        )
    }
}
