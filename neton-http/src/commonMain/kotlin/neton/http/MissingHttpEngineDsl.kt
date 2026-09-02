package neton.http

import neton.core.Neton
import neton.core.component.HttpConfig
import neton.http.client.MissingHttpEngine

/** Server-side twin of the client fallback; see `neton.http.client.MissingHttpEngine`. */
@Deprecated(
    message = "No HTTP engine on the classpath. http { } is provided by an engine module: " +
        "add com.netonstream:neton-http-hyper4k, or depend on com.netonstream:neton which includes it. " +
        "To pick an engine explicitly, call http(::XxxHttpAdapter) { }.",
    level = DeprecationLevel.ERROR,
)
@Suppress("UNUSED_PARAMETER")
fun Neton.LaunchBuilder.http(
    missingEngine: MissingHttpEngine = MissingHttpEngine,
    block: HttpConfig.() -> Unit = {},
): Unit = throw IllegalStateException("No HTTP engine on the classpath")
