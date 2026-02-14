package controller

import neton.core.annotations.*

/**
 * HTTP 方法控制器 - 展示所有 HTTP 方法注解的使用
 * 
 * 本控制器专注展示：
 * - 所有支持的 HTTP 方法注解
 * - RESTful API 设计模式
 * - 同一资源的不同操作方式
 * 
 * 基础路径：/api/products
 */
@Controller("/api/products")
class HttpMethodController {
    
    /**
     * GET - 获取产品列表
     * 用途：查询资源集合
     */
    @Get("/")
    fun getProducts(): String {
        return "📋 GET /api/products - 获取所有产品列表!!!1111"
    }
    
    /**
     * GET - 获取单个产品
     * 用途：查询特定资源
     */
    @Get("/{id}")
    fun getProduct(): String {
        return "📄 GET /api/products/{id} - 获取指定产品详情"
    }
    
    /**
     * POST - 创建新产品
     * 用途：创建新资源
     */
    @Post("/")
    fun createProduct(): String {
        return "✨ POST /api/products - 创建新产品"
    }
    
    /**
     * PUT - 完整更新产品
     * 用途：完整替换资源
     */
    @Put("/{id}")
    fun updateProduct(): String {
        return "🔄 PUT /api/products/{id} - 完整更新产品信息"
    }
    
    /**
     * PATCH - 部分更新产品
     * 用途：部分修改资源
     */
    @Patch("/{id}")
    fun patchProduct(): String {
        return "🔧 PATCH /api/products/{id} - 部分更新产品信息"
    }
    
    /**
     * DELETE - 删除产品
     * 用途：删除资源
     */
    @Delete("/{id}")
    fun deleteProduct(): String {
        return "🗑️ DELETE /api/products/{id} - 删除指定产品"
    }
    
    /**
     * HEAD - 获取产品元信息
     * 用途：获取资源元数据（不返回实体内容）
     */
    @Head("/{id}")
    fun headProduct(): String {
        return "ℹ️ HEAD /api/products/{id} - 获取产品元信息"
    }
    
    /**
     * OPTIONS - 获取支持的方法
     * 用途：获取资源支持的 HTTP 方法
     */
    @Options("/")
    fun optionsProducts(): String {
        return "⚙️ OPTIONS /api/products - 支持的方法: GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS"
    }
    
    /**
     * 批量操作示例 - POST
     * 用途：展示非标准但常见的批量操作
     */
    @Post("/bulk")
    fun bulkOperation(): String {
        return "📦 POST /api/products/bulk - 批量操作产品"
    }
    
    /**
     * 搜索示例 - GET
     * 用途：展示查询操作的变体
     */
    @Get("/search")
    fun searchProducts(): String {
        return "🔍 GET /api/products/search - 搜索产品"
    }
} 