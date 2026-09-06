package neton.ksp

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.kspSourcesDir
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * KSP 生成 SoftDeleteConfig 的契约测试。
 *
 * 与 neton-database 的 SoftDeleteSqlContractTest 配对，两边各锁一半：那边锁「adapter 拿到
 * deletedAtColumn=null 时不拼这个列」，这边锁「生成器只在实体真声明了 deletedAt 时才传列名」。
 * 只有 adapter 那一半的话，「生成器改成硬编码 deleted_at」这条同样能致祸的回归照样进得来 ——
 * 它正是这次事故的另一种写法：`SoftDeleteConfig.deletedAtColumn` 默认 `"deleted_at"` 而生成器
 * 从不传，destroy() 于是对每张只有 `deleted` 列的表拼出不存在的列，全部软删被驱动打回 [42703]。
 *
 * 注解在这里用同名桩重新声明：处理器一律按**全限定名字符串**识别注解，所以 Native-only 的
 * neton-database 不需要（也不可能）出现在 JVM 测试的 classpath 上。
 */
@OptIn(ExperimentalCompilerApi::class)
class EntityTableSoftDeleteConfigTest {

    private val databaseAnnotations = SourceFile.kotlin(
        "DatabaseAnnotationStubs.kt",
        """
        package neton.database.annotations

        annotation class Table(val value: String = "")
        annotation class Entity(val tableName: String = "")
        annotation class Id(val autoGenerate: Boolean = true)
        annotation class Column(val name: String = "", val nullable: Boolean = true, val ignore: Boolean = false)
        annotation class CreatedAt
        annotation class UpdatedAt
        annotation class SoftDelete
        """.trimIndent(),
    )

    /** 跑一遍 KSP 并回读生成的 WidgetTable 源码文本。 */
    private fun generatedTableSource(extraMembers: String): String {
        val fixture = SourceFile.kotlin(
            "Fixture.kt",
            """
            package fixture

            import neton.database.annotations.Id
            import neton.database.annotations.SoftDelete
            import neton.database.annotations.Table

            @Table("widgets")
            data class Widget(
                @Id val id: Long = 0,
                $extraMembers
            )
            """.trimIndent(),
        )
        val compilation = KotlinCompilation().apply {
            sources = listOf(databaseAnnotations, fixture)
            inheritClassPath = true
            messageOutputStream = java.io.OutputStream.nullOutputStream()
            configureKsp {
                symbolProcessorProviders += EntityTableProcessorProvider()
                // 生成物引用 Native-only 的运行时类型（Table / SqlxTableAdapter），在 JVM 上编译不过；
                // 这里要验的是生成器吐出的配置文本，所以只跑处理器不跑后续编译
                withCompilation = false
            }
        }
        val result = compilation.compile()
        val generated = compilation.kspSourcesDir.walkTopDown()
            .filter { it.isFile && it.name == "WidgetTable.kt" }
            .joinToString("\n") { it.readText() }
        assertTrue(generated.isNotBlank(), "KSP 没有生成 WidgetTable：\n${result.messages}")
        return generated
    }

    @Test
    fun entityWithoutDeletedAtGetsNoTimestampColumn() {
        val generated = generatedTableSource("@SoftDelete val deleted: Int = 0,")

        // 逐字锁定生成形态：这就是 SoftDeleteSqlContractTest 里手工构造的那份配置
        assertContains(generated, "SoftDeleteConfig(deletedColumn = \"deleted\", notDeletedValue = 0)")
        assertFalse(
            generated.contains("deletedAtColumn"),
            "实体没有声明 deletedAt，生成物却传了列名 —— destroy() 会拼出不存在的列：\n$generated",
        )
    }

    @Test
    fun entityWithDeletedAtGetsItsColumnName() {
        // 反向守卫：不能因为出过事故就把「填软删时间」这个能力一刀砍掉。
        // 列名取自实体（可被 @Column 改名），不是写死的字面量。
        val generated = generatedTableSource("@SoftDelete val deleted: Int = 0, val deletedAt: Long = 0,")

        assertContains(generated, "deletedAtColumn = \"deleted_at\"")
    }

    @Test
    fun entityWithRenamedDeletedAtColumnGetsTheRenamedColumn() {
        val generated = generatedTableSource(
            "@SoftDelete val deleted: Int = 0, " +
                "@neton.database.annotations.Column(name = \"removed_at\") val deletedAt: Long = 0,",
        )

        assertContains(generated, "deletedAtColumn = \"removed_at\"")
        assertFalse(
            generated.contains("\"deleted_at\""),
            "列名必须跟着 @Column 走，不能写死 deleted_at：\n$generated",
        )
    }
}
