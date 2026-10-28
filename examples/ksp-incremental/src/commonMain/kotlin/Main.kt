package fixture

import neton.database.annotations.Id
import neton.database.annotations.Table

@Table("gate_anchor")
data class Anchor(@Id val id: Long = 0, val label: String = "anchor")

fun main() {
    // Generated symbol must be resolvable by the actual Native compiler/linker.
    check(AnchorTable.toString().isNotEmpty())
    println("NETON_KSP_INCREMENTAL_OK")
}
