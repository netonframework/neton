package neton.core.interfaces

import neton.core.http.HttpContext

/** Application network admission, before authentication and route handlers (including anonymous routes). */
fun interface RequestAdmissionGate {
    /** false means the gate has written the rejection response. Group is resolved routing metadata. */
    suspend fun allow(context: HttpContext, routeGroup: String?): Boolean
}
