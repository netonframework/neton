package neton.http.client

actual fun systemHostResolver(): HostResolver = neton.http.engine.default.client.SystemHostResolver
