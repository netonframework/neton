package config

import neton.core.component.NetonContext
import neton.core.config.NetonConfig
import neton.core.config.NetonConfigurer
import neton.core.interfaces.SecurityBuilder
import neton.security.jwt.JwtAuthenticatorV1

const val JWT_SECRET = "neton-backend-beta1-secret-key-change-in-production"

@NetonConfig("security", order = 0)
class AppSecurityConfig : NetonConfigurer<SecurityBuilder> {
    override fun configure(ctx: NetonContext, target: SecurityBuilder) {
        target.registerJwtAuthenticator(
            secretKey = JWT_SECRET,
            headerName = "Authorization",
            tokenPrefix = "Bearer "
        )
        target.bindDefaultGuard()
    }
}
