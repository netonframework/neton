package compatibility

// The pre-1.0 package migration is intentional, not a hidden typealias surface.
fun obsolete(session: neton.ws.WebSocketSession) = session
