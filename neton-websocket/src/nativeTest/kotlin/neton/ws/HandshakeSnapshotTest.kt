package neton.ws

import neton.core.interfaces.Identity
import kotlin.test.*

class HandshakeSnapshotTest {
    @Test fun identityAndParametersAreDetachedFromMutableInputs() {
        val sourceRoles = mutableSetOf("member")
        val sourcePermissions = mutableSetOf("message:read")
        val identity = object : Identity {
            override val id = "42"
            override val roles: Set<String> get() = sourceRoles
            override val permissions: Set<String> get() = sourcePermissions
        }
        val values = mutableListOf("first", "second")
        val source = mutableMapOf("room" to values)
        val snapshot = HandshakeSnapshot("/ws", "127.0.0.1", true, "chat", identity,
            source, source, mapOf("X-Trace" to values))
        sourceRoles.clear(); sourcePermissions.clear(); values.clear(); source.clear()
        assertEquals(setOf("member"), snapshot.identity!!.roles)
        assertEquals(setOf("message:read"), snapshot.identity!!.permissions)
        assertEquals("first", snapshot.pathParam("room"))
        assertEquals(listOf("first", "second"), snapshot.queryParams("room"))
        assertEquals(listOf("first", "second"), snapshot.headerValues("x-trace"))
        assertTrue(snapshot.headerValues("Authorization").isEmpty())
        assertTrue(snapshot.isSecure)
    }

    @Test fun callersCannotMutateSnapshotThroughReturnedCollections() {
        val snapshot = HandshakeSnapshot("/", "peer", false, null, null,
            emptyMap(), mapOf("q" to listOf("a", "b")), emptyMap())
        val values = snapshot.queryParams("q")
        (values as? MutableList<String>)?.clear()
        assertEquals(listOf("a", "b"), snapshot.queryParams("q"))
    }
}
