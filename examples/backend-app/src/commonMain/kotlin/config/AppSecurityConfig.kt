package config

import neton.core.component.NetonContext
import neton.core.config.ConfigLoader
import neton.core.config.NetonConfig
import neton.core.config.NetonConfigurer
import neton.core.interfaces.SecurityBuilder
import neton.security.jwt.JwtAuthenticatorV1

private const val APP_CONFIG_PATH = "config"
private const val JWT_SECRET_PATH = "security.jwt.secretKey"
private const val JWT_HEADER_NAME_PATH = "security.jwt.headerName"
private const val JWT_TOKEN_PREFIX_PATH = "security.jwt.tokenPrefix"

data class ExampleJwtRuntimeConfig(
    val secretKey: String,
    val headerName: String,
    val tokenPrefix: String
)

fun loadExampleJwtRuntimeConfig(ctx: NetonContext): ExampleJwtRuntimeConfig {
    val appConfig = ConfigLoader.loadApplicationConfig(
        configPath = APP_CONFIG_PATH,
        environment = ConfigLoader.resolveEnvironment(ctx.args),
        args = ctx.args
    )

    val secretKey = ConfigLoader.getString(appConfig, JWT_SECRET_PATH)?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: throw IllegalStateException(
            "Missing JWT secret. Configure security.jwt.secretKey or NETON__SECURITY__JWT__SECRET_KEY."
        )

    val headerName = ConfigLoader.getString(appConfig, JWT_HEADER_NAME_PATH)?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: "Authorization"

    val tokenPrefix = ConfigLoader.getString(appConfig, JWT_TOKEN_PREFIX_PATH)?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: "Bearer "

    return ExampleJwtRuntimeConfig(
        secretKey = secretKey,
        headerName = headerName,
        tokenPrefix = tokenPrefix
    )
}

fun buildExampleJwtAuthenticator(ctx: NetonContext): JwtAuthenticatorV1 {
    val runtimeConfig = loadExampleJwtRuntimeConfig(ctx)
    return JwtAuthenticatorV1(
        secretKey = runtimeConfig.secretKey,
        headerName = runtimeConfig.headerName,
        tokenPrefix = runtimeConfig.tokenPrefix
    )
}

@NetonConfig("security", order = 0)
class AppSecurityConfig : NetonConfigurer<SecurityBuilder> {
    override fun configure(ctx: NetonContext, target: SecurityBuilder) {
        val runtimeConfig = loadExampleJwtRuntimeConfig(ctx)
        target.registerJwtAuthenticator(
            secretKey = runtimeConfig.secretKey,
            headerName = runtimeConfig.headerName,
            tokenPrefix = runtimeConfig.tokenPrefix
        )
        target.bindDefaultGuard()
    }
}
