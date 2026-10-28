package neton.core.security

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import neton.core.http.*
import neton.core.interfaces.Identity
import neton.core.interfaces.SecurityAttributes
import kotlin.test.*

class RequestIdentityIsolationTest {
    private fun request() = object : HttpContext {
        override val traceId = "isolation"
        override val attributes = mutableMapOf<String, Any>()
        override val request: HttpRequest get() = error("unused")
        override val response: HttpResponse get() = error("unused")
        override val session: HttpSession get() = error("unused")
    }
    private fun identity(value: String) = object : Identity {
        override val id = value
        override val roles = emptySet<String>()
        override val permissions = emptySet<String>()
    }

    @Test fun interleavedRequestsNestedViewsAndCancellationRemainIsolated() = runBlocking {
        val requestA = request()
        val a = SecurityContext(requestA)
        val b = SecurityContext(request())
        val ready = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val first = async {
            a.setIdentity(identity("A"))
            ready.complete(Unit)
            resume.await()
            withContext(Dispatchers.Default) { assertEquals("A", a.userId()) }
            assertEquals("A", (requestA.getAttribute(SecurityAttributes.IDENTITY) as Identity).id)
            assertEquals("A", SecurityContext(requestA).userId())
        }
        val second = launch {
            ready.await()
            b.setIdentity(identity("B"))
            resume.complete(Unit)
            try { awaitCancellation() } finally { b.clear() }
        }
        first.await()
        second.cancelAndJoin()
        assertNull(b.currentUser())
        assertEquals("A", a.userId())
        a.setIdentity(null)
        assertNull(a.currentUser())
        assertFalse(a.isAuthenticated())
    }
}
