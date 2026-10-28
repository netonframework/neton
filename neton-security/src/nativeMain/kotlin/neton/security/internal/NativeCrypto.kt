@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package neton.security.internal

import kotlinx.cinterop.*
import neton.openssl.OpenSsl
import neton.openssl.c.*
import platform.posix.size_tVar

internal actual object NativeCrypto {
    private val initialized = Unit.also { OpenSsl.checkVersion() }

    private fun checked(result: Int) {
        check(result == 1) { "OpenSSL operation failed: ${OpenSsl.takeErrors()}" }
    }

    // A non-null pointer also for empty input (OpenSSL distinguishes null from an empty key).
    private inline fun <T> ByteArray.pointer(block: (CPointer<UByteVar>) -> T): T =
        (if (isEmpty()) byteArrayOf(0) else this).usePinned { block(it.addressOf(0).reinterpret()) }

    actual fun digest(algorithm: String, data: ByteArray, size: Int): ByteArray = memScoped {
        OpenSsl.clearErrors()
        val output = ByteArray(size)
        val written = alloc<size_tVar>()
        data.pointer { input -> output.pointer { out ->
            checked(EVP_Q_digest(null, algorithm, null, input, data.size.convert(), out, written.ptr))
        } }
        check(written.value.toInt() == size)
        output
    }

    actual fun hmac(algorithm: String, key: ByteArray, data: ByteArray, size: Int): ByteArray = memScoped {
        OpenSsl.clearErrors()
        val output = ByteArray(size)
        val written = alloc<size_tVar>()
        key.pointer { k -> data.pointer { input -> output.pointer { out ->
            check(EVP_Q_mac(null, "HMAC", null, algorithm, null, k, key.size.convert(),
                input, data.size.convert(), out, size.convert(), written.ptr) != null) {
                "OpenSSL HMAC failed: ${OpenSsl.takeErrors()}"
            }
        } } }
        check(written.value.toInt() == size)
        output
    }

    actual fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, size: Int): ByteArray {
        require(iterations > 0 && size > 0)
        OpenSsl.clearErrors()
        val output = ByteArray(size)
        password.pointer { p -> salt.pointer { s -> output.pointer { out ->
            checked(PKCS5_PBKDF2_HMAC(p.reinterpret(), password.size, s, salt.size,
                iterations, EVP_sha256(), size, out))
        } } }
        return output
    }

    private inline fun <T> withKey(der: ByteArray, privateKey: Boolean, block: (CPointer<EVP_PKEY>) -> T): T = memScoped {
        require(der.isNotEmpty())
        OpenSsl.clearErrors()
        der.pointer { bytes ->
            val cursor = alloc<CPointerVar<UByteVar>>()
            cursor.value = bytes
            val key = (if (privateKey) d2i_AutoPrivateKey(null, cursor.ptr, der.size.convert())
                else d2i_PUBKEY(null, cursor.ptr, der.size.convert()))
                ?: error("Invalid RSA key: ${OpenSsl.takeErrors()}")
            try {
                require(cursor.value == bytes + der.size) { "Trailing key data" }
                require(EVP_PKEY_is_a(key, "RSA") == 1) { "RSA key required" }
                block(key)
            } finally { EVP_PKEY_free(key) }
        }
    }

    actual fun rsaSign(der: ByteArray, data: ByteArray): ByteArray = withKey(der, true) { key -> memScoped {
        val ctx = EVP_MD_CTX_new() ?: error("Unable to allocate digest context")
        try {
            checked(EVP_DigestSignInit(ctx, null, EVP_sha256(), null, key))
            val size = alloc<size_tVar>()
            data.pointer { input ->
                checked(EVP_DigestSign(ctx, null, size.ptr, input, data.size.convert()))
                val output = ByteArray(size.value.toInt())
                output.pointer { out -> checked(EVP_DigestSign(ctx, out, size.ptr, input, data.size.convert())) }
                output.copyOf(size.value.toInt())
            }
        } finally { EVP_MD_CTX_free(ctx) }
    } }

    actual fun rsaVerify(der: ByteArray, data: ByteArray, signature: ByteArray): Boolean = withKey(der, false) { key ->
        val ctx = EVP_MD_CTX_new() ?: error("Unable to allocate digest context")
        try {
            checked(EVP_DigestVerifyInit(ctx, null, EVP_sha256(), null, key))
            signature.pointer { sig -> data.pointer { input ->
                val valid = EVP_DigestVerify(ctx, sig, signature.size.convert(), input, data.size.convert()) == 1
                if (!valid) OpenSsl.clearErrors()
                valid
            } }
        } finally { EVP_MD_CTX_free(ctx) }
    }

    actual fun gcm(key: ByteArray, nonce: ByteArray, data: ByteArray, aad: ByteArray, encrypt: Boolean): ByteArray = memScoped {
        require(key.size in listOf(16, 24, 32) && nonce.isNotEmpty())
        require(encrypt || data.size >= 16)
        require(data.size <= Int.MAX_VALUE - 32)
        OpenSsl.clearErrors()
        val cipher = when (key.size) { 16 -> EVP_aes_128_gcm(); 24 -> EVP_aes_192_gcm(); else -> EVP_aes_256_gcm() }
        val ctx = EVP_CIPHER_CTX_new() ?: error("Unable to allocate cipher context")
        val length = if (encrypt) data.size else data.size - 16
        val output = ByteArray(length + 32)
        try {
            checked(EVP_CipherInit_ex(ctx, cipher, null, null, null, if (encrypt) 1 else 0))
            checked(EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_IVLEN, nonce.size, null))
            key.pointer { k -> nonce.pointer { iv -> checked(EVP_CipherInit_ex(ctx, null, null, k, iv, -1)) } }
            val written = alloc<IntVar>()
            if (aad.isNotEmpty()) aad.pointer { checked(EVP_CipherUpdate(ctx, null, written.ptr, it, aad.size)) }
            output.pointer { out -> data.pointer { input ->
                checked(EVP_CipherUpdate(ctx, out, written.ptr, input, length))
                var total = written.value
                if (!encrypt) checked(EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_TAG, 16, input + length))
                checked(EVP_CipherFinal_ex(ctx, out + total, written.ptr))
                total += written.value
                check(total == length)
                if (encrypt) checked(EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_GET_TAG, 16, out + total))
                output.copyOf(total + if (encrypt) 16 else 0)
            } }
        } finally {
            output.fill(0)
            EVP_CIPHER_CTX_free(ctx)
        }
    }
}
