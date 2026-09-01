package neton.http

/**
 * Joins a group mount with a route pattern. A mount of "/" must not produce a
 * double slash: that is the gateway root-mount contract, see RootMountContractTest.
 */
public fun joinMountPath(mount: String, pattern: String): String {
    val m = mount.trim('/')
    val rel = pattern.trimStart('/')
    return when {
        m.isEmpty() && rel.isEmpty() -> "/"
        m.isEmpty() -> "/$rel"
        rel.isEmpty() -> "/$m"
        else -> "/$m/$rel"
    }
}
