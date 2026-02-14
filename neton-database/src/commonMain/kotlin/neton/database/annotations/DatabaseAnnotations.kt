package neton.database.annotations

import kotlin.reflect.KClass

/**
 * 标记数据库表（KSP 生成用，与 @Entity 等效）
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class Table(val value: String = "")

/**
 * 标记 Repository 接口，KSP 自动生成实现
 * 业务层唯一需要编写的持久层代码
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class Repository(
    val entity: KClass<*> = Any::class,
    val table: String = ""
)

/**
 * 标记数据库实体类
 * @param tableName 数据库表名，如果不指定则使用类名的小写形式
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Entity(val tableName: String = "")

/**
 * 标记主键字段
 * 
 * @param autoGenerate 是否自动生成主键值
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Id(val autoGenerate: Boolean = true)

/**
 * 自定义列映射
 * 
 * @param name 数据库列名
 * @param nullable 是否允许为 null
 * @param ignore 是否忽略此字段
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Column(
    val name: String = "",
    val nullable: Boolean = true,
    val ignore: Boolean = false
)

/**
 * 时间戳字段，用于自动设置创建时间和更新时间
 * 
 * @param onCreate 创建时自动设置
 * @param onUpdate 更新时自动设置
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class Timestamp(
    val onCreate: Boolean = false,
    val onUpdate: Boolean = false
) 