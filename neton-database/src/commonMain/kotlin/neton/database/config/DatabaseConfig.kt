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
         * 从配置文件解析数据库配置
         */
        fun fromMap(configMap: Map<String, Any>): DatabaseConfig {
            val driverStr = configMap["driver"] as? String ?: "MEMORY"
            val driver = try {
                DatabaseDriver.valueOf(driverStr.uppercase())
            } catch (e: Exception) {
                DatabaseDriver.MEMORY
            }
            
            val uri = configMap["uri"] as? String ?: when (driver) {
                DatabaseDriver.POSTGRESQL -> "postgresql://postgres:password@localhost:5432/app"
                DatabaseDriver.MYSQL -> "mysql://root:password@localhost:3306/app"
                DatabaseDriver.SQLITE -> "sqlite://data/app.db"
                DatabaseDriver.MEMORY -> "memory://in-memory"
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
            options = emptyMap() // TODO: 解析查询参数
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
            options = emptyMap()
        )
    }
}

object SqliteUriParser {
    fun parse(uri: String): SqliteUriInfo {
        if (!uri.startsWith("sqlite://")) {
            throw IllegalArgumentException("SQLite URI must start with sqlite://")
        }
        
        val filePath = uri.removePrefix("sqlite://")
        
        return SqliteUriInfo(
            filePath = filePath,
            database = filePath.substringAfterLast('/').substringBefore('.'),
            options = emptyMap()
        )
    }
}

object MemoryUriParser {
    fun parse(uri: String): MemoryUriInfo {
        return MemoryUriInfo()
    }
} 