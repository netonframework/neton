package neton.security.internal

/** Internal bridge to the project's OpenSSL 4 bindings; no provider types escape Security. */
internal expect object NativeCrypto {
    fun digest(algorithm: String, data: ByteArray, size: Int): ByteArray
    fun hmac(algorithm: String, key: ByteArray, data: ByteArray, size: Int): ByteArray
    fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, size: Int): ByteArray
    fun rsaSign(der: ByteArray, data: ByteArray): ByteArray
    fun rsaVerify(der: ByteArray, data: ByteArray, signature: ByteArray): Boolean
    fun gcm(key: ByteArray, nonce: ByteArray, data: ByteArray, aad: ByteArray, encrypt: Boolean): ByteArray
}
