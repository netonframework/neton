package neton.database.config

/**
 * 数据库配置主入口
 * 
 * 使用 URI 格式统一配置，支持：
 * - postgresql://user:pass@host:port/database?options
 * - mysql://user:pass@host:port/database?options  
 * - sqlite://path/to/database.db
 * - memory://in-memory (内存数据库，用于开发测试)
 */
data class DatabaseConfig(
    /**
     * 数据库驱动类型
     */
    val driver: DatabaseDriver,
    
    /**
     * 数据库连接 URI
     */
    val uri: String,
    
    /**
     * 是否开启调试模式
     */
    val debug: Boolean = false,
    
    /**
     * 连接池最大连接数
     */
    val maxConnections: Int = 10,
    
    /**
     * 连接超时时间（毫秒）
     */
    val connectionTimeout: Long = 30000
) {
    
    /**
     * 解析数据库 URI 为具体配置
     */
    fun parseUri(): DatabaseUriInfo {
        return when (driver) {
            DatabaseDriver.POSTGRESQL -> PostgresUriParser.parse(uri)
            DatabaseDriver.MYSQL -> MysqlUriParser.parse(uri)
            DatabaseDriver.SQLITE -> SqliteUriParser.parse(uri)
            DatabaseDriver.MEMORY -> MemoryUriParser.parse(uri)
        }
    }
    
    /**
     * 验证配置有效性
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        
        if (uri.isBlank()) {
            errors.add("数据库 URI 不能为空")
        }
        
        if (maxConnections <= 0) {
            errors.add("连接池大小必须大于 0")
        }
        
        if (connectionTimeout <= 0) {
            errors.add("连接超时时间必须大于 0")
        }
        
        // 验证 URI 格式
        try {
            parseUri()
        } catch (e: Exception) {
            errors.add("数据库 URI 格式错误: ${e.message}")
        }
        
        return errors
    }
    
    companion object {
        /**
         * 从配置文件解析数据库配置。
         *
         * Fail-fast (STD-1): 非法配置直接抛 [IllegalArgumentException]，绝不静默 fallback。
         * 生产环境数据库 URI/driver 写错时框架必须启动失败，而不是悄悄退回内存库导致数据丢失。
         *  - `driver` 缺失 → 抛（不再默认 MEMORY）
         *  - `driver` 未知 → 抛（不再 catch 后 fallback MEMORY）
         *  - 非 MEMORY driver 缺 `uri` → 抛（不再生成硬编码默认库）
         *  - MEMORY driver 的 `uri` 可选（显式选择内存库即视为开发意图）
         */
        fun fromMap(configMap: Map<String, Any>): DatabaseConfig {
            val driverStr = (configMap["driver"] as? String)?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException(
                    "database config missing required 'driver' (one of: POSTGRESQL, MYSQL, SQLITE, MEMORY)"
                )
            val driver = try {
                DatabaseDriver.valueOf(driverStr.uppercase())
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException(
                    "Unknown database driver '$driverStr' (valid: POSTGRESQL, MYSQL, SQLITE, MEMORY)"
                )
            }

            val uriRaw = (configMap["uri"] as? String)?.takeIf { it.isNotBlank() }
            val uri = when {
                uriRaw != null -> uriRaw
                driver == DatabaseDriver.MEMORY -> "memory://in-memory"
                else -> throw IllegalArgumentException(
                    "database config missing required 'uri' for driver $driver"
                )
            }

            return DatabaseConfig(
                driver = driver,
                uri = uri,
                debug = configMap["debug"] as? Boolean ?: false,
                maxConnections = (configMap["maxConnections"] as? Number)?.toInt() ?: 10,
                connectionTimeout = (configMap["connectionTimeout"] as? Number)?.toLong() ?: 30000
            )
        }
    }
}

/**
 * 支持的数据库驱动类型
 */
enum class DatabaseDriver {
    POSTGRESQL,
    MYSQL,
    SQLITE,
    MEMORY
}

/**
 * 数据库 URI 解析信息
 */
sealed class DatabaseUriInfo {
    abstract val database: String
    abstract val options: Map<String, String>
}

/**
 * PostgreSQL URI 信息
 */
data class PostgresUriInfo(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    override val database: String,
    override val options: Map<String, String>
) : DatabaseUriInfo()

/**
 * MySQL URI 信息  
 */
data class MysqlUriInfo(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    override val database: String,
    override val options: Map<String, String>
) : DatabaseUriInfo()

/**
 * SQLite URI 信息
 */
data class SqliteUriInfo(
    val filePath: String,
    override val database: String,
    override val options: Map<String, String>
) : DatabaseUriInfo()

/**
 * 内存数据库 URI 信息
 */
data class MemoryUriInfo(
    override val database: String = "memory",
    override val options: Map<String, String> = emptyMap()
) : DatabaseUriInfo()

/**
 * URI 解析器
 */
/**
 * 解析 URI 的 `?k=v&k2=v2` 查询串为 options map。
 * STD-1: 不再静默丢弃查询参数（旧实现 `emptyMap() // TODO`）。空/无查询串返回空 map。
 * 缺 '=' 或键为空的片段被跳过（不静默塞进 map，也不 fail 整个解析）。
 */
internal fun parseQueryOptions(uri: String): Map<String, String> {
    val query = uri.substringAfter('?', "")
    if (query.isBlank()) return emptyMap()
    return query.split('&').mapNotNull { pair ->
        val idx = pair.indexOf('=')
        if (idx <= 0) null else pair.substring(0, idx) to pair.substring(idx + 1)
    }.toMap()
}

object PostgresUriParser {
    fun parse(uri: String): PostgresUriInfo {
        // 简化实现，实际应该使用更健壮的 URI 解析
        val regex = Regex("postgresql://([^:]+):([^@]+)@([^:]+):(\\d+)/([^?]+)(\\?.*)?")
        val match = regex.find(uri)
            ?: throw IllegalArgumentException("Invalid PostgreSQL URI format: $uri")

        val (username, password, host, port, database) = match.destructured

        return PostgresUriInfo(
            host = host,
            port = port.toInt(),
            username = username,
            password = password,
            database = database,
            options = parseQueryOptions(uri)
        )
    }
}

object MysqlUriParser {
    fun parse(uri: String): MysqlUriInfo {
        val regex = Regex("mysql://([^:]+):([^@]+)@([^:]+):(\\d+)/([^?]+)(\\?.*)?")
        val match = regex.find(uri)
            ?: throw IllegalArgumentException("Invalid MySQL URI format: $uri")

        val (username, password, host, port, database) = match.destructured

        return MysqlUriInfo(
            host = host,
            port = port.toInt(),
            username = username,
            password = password,
            database = database,
            options = parseQueryOptions(uri)
        )
    }
}

object SqliteUriParser {
    fun parse(uri: String): SqliteUriInfo {
        if (!uri.startsWith("sqlite://")) {
            throw IllegalArgumentException("SQLite URI must start with sqlite://")
        }

        val pathAndQuery = uri.removePrefix("sqlite://")
        val filePath = pathAndQuery.substringBefore('?')

        return SqliteUriInfo(
            filePath = filePath,
            database = filePath.substringAfterLast('/').substringBefore('.'),
            options = parseQueryOptions(uri)
        )
    }
}

object MemoryUriParser {
    fun parse(uri: String): MemoryUriInfo {
        return MemoryUriInfo()
    }
} 