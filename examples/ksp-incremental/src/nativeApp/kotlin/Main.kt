package fixture

import neton.database.annotations.Id
import neton.database.annotations.Table
import neton.application.generated.GeneratedApplicationModules

@Table("gate_anchor")
data class Anchor(@Id val id: Long = 0, val label: String = "anchor")

fun main() {
    // Generated symbol must be resolvable by the actual Native compiler/linker.
    check(AnchorTable.toString().isNotEmpty())
    check(GeneratedApplicationModules.modules.map { it.moduleId } == listOf("billing", "catalog"))
    check(GeneratedApplicationModules.manifestSha256.length == 64)
    println("NETON_KSP_INCREMENTAL_OK")
}
