package neton.security.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import kotlin.test.Test
import kotlin.time.Clock

/**
 * 附件加密的吞吐基线：决定「上传时加一次密」到底要不要额外的机器。
 *
 * 不是断言正确性，是**量成本**——所以不设阈值断言（不同机器差异大，卡阈值只会造成假失败），
 * 只把数字打出来，决策时看数字。
 */
class AesGcmThroughputBench {

    private fun gcm(key: ByteArray) = CryptographyProvider.Default
        .get(AES.GCM).keyDecoder().decodeFromByteArrayBlocking(AES.Key.Format.RAW, key)
        .cipher()

    @Test
    fun measure_attachment_sized_payloads() {
        val key = ByteArray(32) { (it * 7 + 1).toByte() }
        val cipher = gcm(key)
        // 覆盖真实附件档位：头像 / 聊天图 / 高清图 / 短视频
        listOf(64 * 1024 to "64KB 头像", 512 * 1024 to "512KB 聊天图",
               3 * 1024 * 1024 to "3MB 高清图", 20 * 1024 * 1024 to "20MB 短视频").forEach { (size, label) ->
            val data = ByteArray(size) { (it and 0xFF).toByte() }
            cipher.encryptBlocking(data) // 预热，排除首次初始化
            val rounds = if (size <= 512 * 1024) 20 else 5
            val t0 = Clock.System.now()
            repeat(rounds) { cipher.encryptBlocking(data) }
            val ms = (Clock.System.now() - t0).inWholeMicroseconds / 1000.0
            val perOp = ms / rounds
            val mbPerSec = (size / 1024.0 / 1024.0) / (perOp / 1000.0)
            println("[bench] $label: 单次 ${(perOp * 100).toInt() / 100.0} ms, 吞吐 ${mbPerSec.toInt()} MB/s")
        }
    }
}
