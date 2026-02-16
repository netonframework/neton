package neton.ksp

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import java.io.OutputStreamWriter

class JobProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {

    private val jobAnnotationName = "neton.jobs.Job"
    private val jobExecutorInterface = "neton.jobs.JobExecutor"

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation(jobAnnotationName)
        val jobClasses = symbols.filterIsInstance<KSClassDeclaration>().toList()

        if (jobClasses.isEmpty()) {
            return emptyList()
        }

        logger.info("Found ${jobClasses.size} @Job classes to process")

        // 编译期校验
        val validated = validate(jobClasses)
        if (validated.isEmpty()) return emptyList()

        generateJobRegistry(validated)
        return emptyList()
    }

    private fun validate(jobClasses: List<KSClassDeclaration>): List<JobEntry> {
        val entries = mutableListOf<JobEntry>()
        val seenIds = mutableSetOf<String>()

        for (clazz in jobClasses) {
            val fqn = clazz.qualifiedName?.asString() ?: continue
            val ann = clazz.annotations.firstOrNull { it.shortName.asString() == "Job" } ?: continue

            // 1. 必须实现 JobExecutor
            val implementsExecutor = clazz.superTypes.any { superType ->
                val resolved = superType.resolve()
                val superFqn = (resolved.declaration as? KSClassDeclaration)?.qualifiedName?.asString()
                superFqn == jobExecutorInterface
            }
            if (!implementsExecutor) {
                logger.error("@Job class '$fqn' must implement $jobExecutorInterface", clazz)
                continue
            }

            // 提取注解参数
            val id = ann.arguments.find { it.name?.asString() == "id" }?.value as? String ?: ""
            val cron = ann.arguments.find { it.name?.asString() == "cron" }?.value as? String ?: ""
            val fixedRate = ann.arguments.find { it.name?.asString() == "fixedRate" }?.value as? Long ?: 0L
            val initialDelay = ann.arguments.find { it.name?.asString() == "initialDelay" }?.value as? Long ?: 0L
            val lockTtlMs = ann.arguments.find { it.name?.asString() == "lockTtlMs" }?.value as? Long ?: 30_000L
            val enabled = ann.arguments.find { it.name?.asString() == "enabled" }?.value as? Boolean ?: true

            // mode 是枚举，需要特殊处理
            val modeArg = ann.arguments.find { it.name?.asString() == "mode" }
            val modeValue = extractEnumValue(modeArg) ?: "SINGLE_NODE"

            // 2. id 非空
            if (id.isBlank()) {
                logger.error("@Job.id must not be blank for class '$fqn'", clazz)
                continue
            }

            // 3. id 不重复
            if (id in seenIds) {
                logger.error("Duplicate @Job.id '$id' found in class '$fqn'", clazz)
                continue
            }
            seenIds.add(id)

            // 4. cron 和 fixedRate 互斥
            val hasCron = cron.isNotBlank()
            val hasFixedRate = fixedRate > 0
            if (hasCron && hasFixedRate) {
                logger.error("@Job '$id': cron and fixedRate are mutually exclusive", clazz)
                continue
            }
            if (!hasCron && !hasFixedRate) {
                logger.error("@Job '$id': must specify either cron or fixedRate", clazz)
                continue
            }

            // 5. lockTtlMs > 0
            if (lockTtlMs <= 0) {
                logger.error("@Job '$id': lockTtlMs must be > 0", clazz)
                continue
            }

            // 6. ALL_NODES + lockTtlMs 显式配置 → warn
            if (modeValue == "ALL_NODES") {
                val lockTtlExplicit = ann.arguments.find { it.name?.asString() == "lockTtlMs" }
                if (lockTtlExplicit != null && (lockTtlExplicit.value as? Long ?: 30_000L) != 30_000L) {
                    logger.warn("@Job '$id': lockTtlMs is ignored when mode=ALL_NODES", clazz)
                }
            }

            entries.add(
                JobEntry(
                    clazz = clazz,
                    fqn = fqn,
                    id = id,
                    cron = cron,
                    fixedRate = fixedRate,
                    initialDelay = initialDelay,
                    mode = modeValue,
                    lockTtlMs = lockTtlMs,
                    enabled = enabled
                )
            )
        }

        return entries
    }

    private fun extractEnumValue(arg: KSValueArgument?): String? {
        if (arg == null) return null
        val value = arg.value ?: return null
        // KSP 表示枚举值的方式可能是 KSType 或字符串
        return value.toString().substringAfterLast(".")
    }

    private data class JobEntry(
        val clazz: KSClassDeclaration,
        val fqn: String,
        val id: String,
        val cron: String,
        val fixedRate: Long,
        val initialDelay: Long,
        val mode: String,
        val lockTtlMs: Long,
        val enabled: Boolean
    )

    private fun generateJobRegistry(entries: List<JobEntry>) {
        val file = codeGenerator.createNewFile(
            dependencies = Dependencies(true, *entries.mapNotNull { it.clazz.containingFile }.toTypedArray()),
            packageName = "neton.jobs.generated",
            fileName = "GeneratedJobRegistry"
        )

        OutputStreamWriter(file).use { writer ->
            writer.write(
                """
// AUTO-GENERATED by neton-ksp JobProcessor - DO NOT EDIT
package neton.jobs.generated

import neton.jobs.*
import neton.core.component.NetonContext

object GeneratedJobRegistry : JobRegistry {
    override val jobs: List<JobDefinition> = listOf(
""".trimStart()
            )

            entries.forEachIndexed { index, entry ->
                val schedule = if (entry.cron.isNotBlank()) {
                    "JobSchedule.Cron(\"${entry.cron}\")"
                } else {
                    "JobSchedule.FixedRate(intervalMs = ${entry.fixedRate}L, initialDelayMs = ${entry.initialDelay}L)"
                }

                val instantiation = buildInstantiation(entry.clazz)

                writer.write(
                    """        JobDefinition(
            id = "${entry.id}",
            schedule = $schedule,
            mode = ExecutionMode.${entry.mode},
            lockTtlMs = ${entry.lockTtlMs}L,
            enabled = ${entry.enabled},
            factory = { ctx: NetonContext ->
                $instantiation
            }
        )"""
                )
                if (index < entries.size - 1) writer.write(",\n")
                else writer.write("\n")
            }

            writer.write(
                """    )
}
"""
            )
        }
    }

    private fun buildInstantiation(clazz: KSClassDeclaration): String {
        val fqn = clazz.qualifiedName!!.asString()
        val params = clazz.primaryConstructor?.parameters ?: return "$fqn()"
        if (params.isEmpty()) return "$fqn()"

        val args = params.map { p ->
            val decl = p.type.resolve().declaration
            val typeName = (decl as? KSClassDeclaration)?.qualifiedName?.asString()
                ?: p.type.resolve().toString()
            val typeStr = p.type.resolve().toString()
            when {
                typeName == "neton.core.component.NetonContext" || typeStr.contains("NetonContext") -> "ctx"
                typeName == "neton.logging.Logger" || typeStr.contains("neton.logging.Logger") ->
                    "ctx.get(neton.logging.LoggerFactory::class).get(\"$fqn\")"

                else -> "ctx.get($typeName::class)"
            }
        }
        return "$fqn(${args.joinToString(", ")})"
    }
}
