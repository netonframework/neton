package neton.security

/** Compatibility name; identity state belongs exclusively to the supplied request. */
@Deprecated("Use neton.core.security.SecurityContext(context)", ReplaceWith("SecurityContext", "neton.core.security.SecurityContext"))
typealias SecurityContext = neton.core.security.SecurityContext
