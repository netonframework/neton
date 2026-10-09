package neton.http.hyper4k

import neton.core.Neton
import neton.core.component.HttpConfig
import neton.core.component.NetonContext
import neton.core.http.adapter.HttpAdapter
import neton.http.HttpComponent
import neton.http.engine.default.DefaultHttpAdapter
import neton.http.http
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertIs

class HttpEngineSelectionTest {
    @Test
    fun hyper4kDependencyDoesNotReplaceTheDefault() = runBlocking {
        val ctx = NetonContext(emptyArray())
        HttpComponent().init(ctx, HttpConfig())
        assertIs<DefaultHttpAdapter>(ctx.get<HttpAdapter>())
        Neton.LaunchBuilder().http { port = 8080 }
    }

    @Test
    fun hyper4kCanStillBeSelectedExplicitly() = runBlocking {
        val ctx = NetonContext(emptyArray())
        HttpComponent(::Hyper4kHttpAdapter).init(ctx, HttpConfig())
        assertIs<Hyper4kHttpAdapter>(ctx.get<HttpAdapter>())
        Neton.LaunchBuilder().http(::Hyper4kHttpAdapter) { port = 8080 }
    }
}
