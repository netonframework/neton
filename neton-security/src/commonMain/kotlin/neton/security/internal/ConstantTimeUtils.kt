package neton.security.internal

/**
 * Constant-time 字节比较，防止时序侧信道泄漏。
 * JWT 签名校验必须使用此函数，不得用 ==。
 *
 * @see Neton-JWT-Authenticator-Spec-v1.md 细则 6
 */
fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var r = 0
    for (i in a.indices) r = r or (a[i].toInt() xor b[i].toInt())
    return r == 0
}
