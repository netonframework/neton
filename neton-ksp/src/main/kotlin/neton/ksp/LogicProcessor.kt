package neton.ksp

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import java.io.OutputStreamWriter

/**
 * Logic 自动装配处理器（LOGIC-P0）。
 *
 * 扫描 `@neton.core.annotations.Logic` 标注的类，按 primary constructor 生成
 * 构造 + 注册代码，聚合为 `{ModuleId}LogicInitializer`，消灭 ModuleInitializer
 * 里手写的机械 `val x = X(...); ctx.bind(X::class, x)` 样板。
 *
 * P0 装配规则（与 @Logic KDoc 冻结一致）：
 * 1. 只支持 primary constructor。
 * 2. 参数解析：
 *    - `neton.logging.Logger`        → `ctx.get(LoggerFactory::class).get("<@Logic.logger 或类 FQN>")`
 *    - `neton.logging.LoggerFactory` → `ctx.get(LoggerFactory::class)`
 *    - `neton.core.component.NetonContext` → `ctx`
 *    - 其余 class 类型               → `ctx.get(<FQN>::class)`
 *    - 非 class 类型（泛型参数 / 函数类型）→ 编译期 error
 * 3. @Logic 类之间按构造依赖拓扑排序；循环依赖编译期 error。
 * 4. 生成 `if (ctx.getOrNull(X::class) == null) ctx.bind(X::class, X(...))` —
 *    手写 bootstrap 先 bind 的对象永远优先（escape hatch 不需要新机制）。
 *
 * 生成物：`neton.module.{moduleId}.generated.{PascalId}LogicInitializer`；
 * 同时把调用片段写入 ModuleFragmentSink（供 ModuleInitializerProcessor 聚合）。
 *
 * 需要 KSP 选项 `neton.moduleId`；未配置时跳过（与 Controller 兼容模式不同，
 * @Logic 没有全局兼容模式 — 装配必须按模块隔离）。
 */
class LogicProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String> = emptyMap()
) : SymbolProcessor {

    private val moduleId: String? = options["neton.moduleId"]?.takeIf { it.isNotBlank() }
    private val logicAnnotationName = "neton.core.annotations.Logic"

    private var invoked = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (invoked) return emptyList()

        val symbols = resolver.getSymbolsWithAnnotation(logicAnnotationName)
        val classes = symbols.filterIsInstance<KSClassDeclaration>().toList()
        if (classes.isEmpty()) return emptyList()
        invoked = true

        if (moduleId == null) {
            logger.error(
                "@Logic classes found (${classes.size}) but KSP option 'neton.moduleId' is not set. " +
                        "Add ksp { arg(\"neton.moduleId\", \"<id>\") } to build.gradle.kts."
            )
            return emptyList()
        }

        logger.info("LogicProcessor[$moduleId]: found ${classes.size} @Logic class(es)")

        val sorted = topologicalSort(classes) ?: return emptyList()
        generateInitializer(moduleId, sorted)
        return emptyList()
    }

    // ---------- 拓扑排序（DFS post-order; 循环依赖报错） ----------

    private fun topologicalSort(classes: List<KSClassDeclaration>): List<KSClassDeclaration>? {
        val byFqn = classes.associateBy { it.qualifiedName!!.asString() }
        val sorted = mutableListOf<KSClassDeclaration>()
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        var failed = false

        fun visit(c: KSClassDeclaration, path: List<String>) {
            val fqn = c.qualifiedName!!.asString()
            if (fqn in visited) return
            if (fqn in visiting) {
                logger.error(
                    "@Logic circular dependency: ${(path + fqn).joinToString(" -> ")}",
                    c
                )
                failed = true
                return
            }
            visiting.add(fqn)
            c.primaryConstructor?.parameters?.forEach { p ->
                val depFqn = p.type.resolve().declaration.qualifiedName?.asString()
                val dep = depFqn?.let { byFqn[it] }
                if (dep != null) visit(dep, path + fqn)
            }
            visiting.remove(fqn)
            visited.add(fqn)
            sorted.add(c)
        }

        // 排序稳定性：按 FQN 排序后再 DFS，保证生成文件内容可重现（增量编译友好）
        classes.sortedBy { it.qualifiedName!!.asString() }.forEach { visit(it, emptyList()) }
        return if (failed) null else sorted
    }

    // ---------- 代码生成 ----------

    private fun generateInitializer(moduleId: String, classes: List<KSClassDeclaration>) {
        val pkg = "neton.module.$moduleId.generated"
        val className = "${moduleId.toPascal()}LogicInitializer"

        // 第一遍：全量校验 + 构建 bind 块。任何参数不可解析 → 已报 error，整体放弃
        // （不创建半截生成文件，让编译以清晰的 KSP error 失败）。
        data class BindBlock(val simpleName: String, val fqn: String, val args: List<String>)

        val blocks = mutableListOf<BindBlock>()
        for (c in classes) {
            val fqn = c.qualifiedName!!.asString()
            val ctor = c.primaryConstructor
            if (ctor == null) {
                logger.error("@Logic class $fqn has no primary constructor", c)
                return
            }
            val args = mutableListOf<String>()
            for (p in ctor.parameters) {
                val expr = paramExpression(c, p) ?: return
                args.add(expr)
            }
            blocks.add(BindBlock(c.simpleName.asString(), fqn, args))
        }

        // fragment：聚合进 generated module initializer（per-request ctx.get 使顺序不敏感，
        // 但仍声明在 routes 之前执行更直观）
        ModuleFragmentSink.addStat(moduleId, "logics", classes.size)
        ModuleFragmentSink.addImport(moduleId, "import $pkg.$className")
        ModuleFragmentSink.addFragment(
            moduleId,
            "logics",
            "注册 Logic 组件（${classes.size} 个）",
            "        $className.initialize(ctx)"
        )

        val file = codeGenerator.createNewFile(
            dependencies = Dependencies(true, *classes.mapNotNull { it.containingFile }.toTypedArray()),
            packageName = pkg,
            fileName = className
        )

        OutputStreamWriter(file).use { w ->
            w.write("// AUTO-GENERATED by Neton KSP LogicProcessor - DO NOT EDIT\n")
            w.write("package $pkg\n\n")
            w.write("import neton.core.component.NetonContext\n\n")
            w.write("/**\n")
            w.write(" * 模块 [$moduleId] 的 @Logic 自动装配（${classes.size} 个组件，依赖拓扑序）。\n")
            w.write(" *\n")
            w.write(" * 全部 absent-才-bind：手写 bootstrap 先 ctx.bind 的对象不会被覆盖。\n")
            w.write(" * 在手写 ModuleInitializer 中调用 `$className.initialize(ctx)`。\n")
            w.write(" */\n")
            w.write("object $className {\n\n")
            w.write("    fun initialize(ctx: NetonContext) {\n")

            for (b in blocks) {
                w.write("        // ${b.simpleName}\n")
                w.write("        if (ctx.getOrNull(${b.fqn}::class) == null) {\n")
                if (b.args.isEmpty()) {
                    w.write("            ctx.bind(${b.fqn}::class, ${b.fqn}())\n")
                } else {
                    w.write("            ctx.bind(${b.fqn}::class, ${b.fqn}(\n")
                    b.args.forEach { w.write("                $it,\n") }
                    w.write("            ))\n")
                }
                w.write("        }\n\n")
            }

            w.write("    }\n")
            w.write("}\n")
        }

        logger.info("Generated $pkg.$className with ${classes.size} logic component(s)")
    }

    /** 单个构造参数 → 解析表达式；不可解析返回 null（已报 error）。 */
    private fun paramExpression(owner: KSClassDeclaration, p: KSValueParameter): String? {
        val resolved = p.type.resolve()
        val decl = resolved.declaration
        val fqn = decl.qualifiedName?.asString()
        if (fqn == null || decl !is KSClassDeclaration) {
            logger.error(
                "@Logic ${owner.qualifiedName!!.asString()}: constructor parameter " +
                        "'${p.name?.asString()}' type '$resolved' is not injectable " +
                        "(only class types are supported in P0)",
                p
            )
            return null
        }
        return when (fqn) {
            "neton.logging.Logger" -> {
                val name = loggerNameOf(owner)
                "ctx.get(neton.logging.LoggerFactory::class).get(\"$name\")"
            }
            "neton.logging.LoggerFactory" -> "ctx.get(neton.logging.LoggerFactory::class)"
            "neton.core.component.NetonContext" -> "ctx"
            else -> "ctx.get($fqn::class)"
        }
    }

    /** @Logic(logger = "...") 的 logger 名；空串退化为类 FQN。 */
    private fun loggerNameOf(c: KSClassDeclaration): String {
        val ann = c.annotations.firstOrNull {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == logicAnnotationName
        }
        val v = ann?.arguments?.firstOrNull { it.name?.asString() == "logger" }?.value as? String
        return if (!v.isNullOrBlank()) v else c.qualifiedName!!.asString()
    }

    private fun String.toPascal(): String =
        split('-', '_', '.').joinToString("") { part -> part.replaceFirstChar { it.uppercaseChar() } }
}

class LogicProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return LogicProcessor(
            environment.codeGenerator,
            environment.logger,
            environment.options
        )
    }
}
