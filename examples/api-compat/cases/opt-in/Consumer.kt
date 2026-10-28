@file:OptIn(neton.core.annotations.InternalNetonApi::class, neton.websocket.spi.ExperimentalWebSocketEngineApi::class)
package compatibility

fun internalDefaults() = neton.http.client.HttpClientConfig().toEffectiveTimeout()
fun databaseSupport() = neton.database.adapter.sqlx.SqlxDatabase.currentDriver()
fun engineOptions() = neton.websocket.spi.EngineLimits()
