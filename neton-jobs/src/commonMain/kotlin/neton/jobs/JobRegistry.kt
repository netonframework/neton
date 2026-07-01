package neton.jobs

interface JobRegistry {
    val jobs: List<JobDefinition>
}

/** Mutable only during application registration; JobsComponent snapshots it during prepare. */
class MutableJobRegistry(initial: List<JobDefinition> = emptyList()) : JobRegistry {
    private val definitions = mutableListOf<JobDefinition>()
    private val ids = mutableSetOf<String>()

    init {
        registerAll(initial)
    }

    override val jobs: List<JobDefinition> get() = definitions.toList()

    fun registerAll(jobs: Collection<JobDefinition>) {
        for (job in jobs) {
            check(ids.add(job.id)) { "Duplicate job id '${job.id}'" }
            definitions += job
        }
    }
}
