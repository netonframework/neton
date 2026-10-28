@file:OptIn(neton.core.annotations.InternalNetonApi::class, neton.ai.ExperimentalNetonAiApi::class, neton.ws.spi.ExperimentalWebSocketEngineApi::class)
package compatibility

fun internalDefaults() = neton.http.client.HttpClientConfig().toEffectiveTimeout()
fun databaseSupport() = neton.database.adapter.sqlx.SqlxDatabase.currentDriver()
fun engineOptions() = neton.ws.spi.EngineLimits()
fun aiClient(value: neton.ai.AiClient) = value
