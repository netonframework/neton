package neton.ws

import neton.core.http.HttpContext
import neton.core.interfaces.Identity
import neton.core.interfaces.SecurityAttributes

internal class HandshakeSnapshot(
    override val path: String,
    override val peerAddress: String,
    override val isSecure: Boolean,
    override val subprotocol: String?,
    identity: Identity?,
    paths: Map<String, List<String>>,
    queries: Map<String, List<String>>,
    headers: Map<String, List<String>>,
) : HandshakeInfo {
    override val identity: Identity? = identity?.let { FrozenIdentity(it.id, it.roles, it.permissions) }
    private val paths = paths.mapValues { it.value.toList() }
    private val queries = queries.mapValues { it.value.toList() }
    private val headers = headers.mapKeys { it.key.lowercase() }.mapValues { it.value.toList() }
    override fun pathParam(name: String): String? = paths[name]?.firstOrNull()
    override fun queryParams(name: String): List<String> = queries[name]?.toList() ?: emptyList()
    override fun headerValues(name: String): List<String> = headers[name.lowercase()]?.toList() ?: emptyList()
}

private class FrozenIdentity(override val id: String, roles: Set<String>, permissions: Set<String>) : Identity {
    private val roleValues = roles.toSet()
    private val permissionValues = permissions.toSet()
    override val roles: Set<String> get() = roleValues.toSet()
    override val permissions: Set<String> get() = permissionValues.toSet()
}

internal class HandshakeSnapshotTooLarge : IllegalStateException("Handshake snapshot budget exceeded")

internal fun snapshotHandshake(context: HttpContext, selected: String?, config: WebSocketConfig): HandshakeInfo {
    val request = context.request
    val identity = context.getAttribute(SecurityAttributes.IDENTITY) as? Identity
    var remaining = config.maxHandshakeSnapshotBytes
    fun count(value: String) {
        val bytes = utf8Bytes(value)
        if (bytes > remaining) throw HandshakeSnapshotTooLarge()
        remaining -= bytes
    }
    count(request.path); count(request.peerAddress); selected?.let(::count)
    identity?.let { count(it.id); it.roles.forEach(::count); it.permissions.forEach(::count) }
    val paths = request.pathParams.toMap()
    val queries = request.queryParams.toMap()
    val headers = config.handshakeHeaderNames.associateWith { request.headers.getAll(it) }
    for (map in listOf(paths, queries, headers)) for ((key, values) in map) {
        count(key); values.forEach(::count)
    }
    return HandshakeSnapshot(request.path, request.peerAddress, request.isSecure, selected, identity, paths, queries, headers)
}
