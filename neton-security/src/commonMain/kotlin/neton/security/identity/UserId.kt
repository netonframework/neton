package neton.security.identity

/**
 * 强类型用户 ID（value class 包 ULong）
 *
 * @see Neton-Security-Spec-v1.1-API-Freeze.md
 */
value class UserId(val value: ULong) {
    companion object {
        /**
         * 从字符串解析，跨边界入口。
         * 失败抛 AuthenticationException(code="InvalidUserId", message="Invalid user id", path="sub")
         */
        fun parse(s: String): UserId {
            val trimmed = s.trim()
            if (trimmed.isEmpty()) throw AuthenticationException("InvalidUserId", "Invalid user id", "sub")
            return try {
                UserId(trimmed.toULong())
            } catch (_: NumberFormatException) {
                throw AuthenticationException("InvalidUserId", "Invalid user id", "sub")
            }
        }
    }
}
