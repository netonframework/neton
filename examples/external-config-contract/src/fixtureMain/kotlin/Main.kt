import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import neton.core.Neton
import neton.core.config.ConfigLoader
import neton.core.config.ExternalConfig
import neton.core.config.getEnv
import neton.http.client.HttpClient
import neton.http.client.create
import neton.http.config.ConsulKvConfigSource
import neton.http.http
import neton.routing.get
import neton.routing.routing

fun main(args: Array<String>) {
    val endpoint = getEnv("FIXTURE_CONSUL").takeUnless { getEnv("FIXTURE_DISABLE") == "1" }
    val client = endpoint?.let { HttpClient.create {} }
    val gate = CompletableDeferred<Unit>()
    val entered = CompletableDeferred<Unit>()
    var external: ExternalConfig? = null
    var startupX = ""
    try {
        Neton.run(args) {
            if (endpoint != null) {
                externalConfig(
                    ConsulKvConfigSource(
                        endpoint, "fixture", "test", setOf("left.conf", "left.test.conf", "right.conf", "cold.conf"),
                        getEnv("FIXTURE_TOKEN"), client = checkNotNull(client),
                    ),
                    hotFiles = setOf("left.conf", "left.test.conf", "right.conf"),
                    pollMillis = 200,
                    retryMillis = 250,
                ) { snapshot ->
                    val left = snapshot.module("left")
                    require(left["x"] is String && left["pair"] is String)
                    require(left["pair"] == snapshot.module("right")["pair"])
                    require(snapshot.module("cold")["value"] is String)
                }
            }
            http { port = getEnv("FIXTURE_PORT")!!.toInt() }
            onStart {
                if (endpoint != null) external = get<ExternalConfig>()
                startupX = ConfigLoader.loadModuleConfig("left", environment = "test", args = args)?.get("x").toString()
            }
            routing {
                get("/state") { ctx ->
                    val snapshot = external?.snapshot()
                    val left = snapshot?.module("left")
                    val first = left?.get("pair")
                    if (ctx.request.queryParams["hold"] == "1") {
                        entered.complete(Unit)
                        withTimeout(20_000) { gate.await() }
                    }
                    val right = snapshot?.module("right")
                    val cold = snapshot?.module("cold")
                    ctx.response.text(listOf(snapshot?.release ?: "local", first, right?.get("pair"),
                        cold?.get("value"), left?.get("x") ?: startupX, startupX,
                        left?.get("optional") ?: "default").joinToString("|"))
                }
                get("/entered") { ctx -> ctx.response.text(entered.isCompleted.toString()) }
                get("/resume") { ctx -> gate.complete(Unit); ctx.response.text("ok") }
            }
        }
    } finally {
        runBlocking { client?.close() }
    }
}
