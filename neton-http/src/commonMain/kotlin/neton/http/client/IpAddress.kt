package neton.http.client

/**
 * An IP address, IPv4 (4 bytes) or IPv6 (16 bytes), as an outbound [OutboundAddressPolicy] sees it.
 *
 * Immutable. [toString] is a literal every resolver accepts without a lookup (dotted IPv4, full
 * colon-hex IPv6), which is what the built-in client dials after a policy admitted the address.
 */
class IpAddress private constructor(private val bytes: ByteArray) {

    val isIpv4: Boolean get() = bytes.size == 4
    val isIpv6: Boolean get() = bytes.size == 16

    /** The address bytes (a copy). */
    fun toByteArray(): ByteArray = bytes.copyOf()

    internal operator fun get(i: Int): Int = bytes[i].toInt() and 0xff

    internal val size: Int get() = bytes.size

    /**
     * The IPv4 address an IPv6 address carries for IPv4 traffic: IPv4-mapped (`::ffff:a.b.c.d`),
     * IPv4-compatible (`::a.b.c.d`), NAT64 (`64:ff9b::a.b.c.d`) and 6to4 (`2002:aabb:ccdd::/48`).
     * Null for IPv4 and for every other IPv6 address.
     */
    fun embeddedIpv4(): IpAddress? {
        if (isIpv4) return null
        val zero10 = (0 until 10).all { bytes[it] == 0.toByte() }
        return when {
            zero10 && this[10] == 0xff && this[11] == 0xff -> of(bytes.copyOfRange(12, 16))
            zero10 && this[10] == 0 && this[11] == 0 -> of(bytes.copyOfRange(12, 16))
            this[0] == 0x00 && this[1] == 0x64 && this[2] == 0xff && this[3] == 0x9b &&
                (4 until 12).all { bytes[it] == 0.toByte() } -> of(bytes.copyOfRange(12, 16))
            this[0] == 0x20 && this[1] == 0x02 -> of(bytes.copyOfRange(2, 6))
            else -> null
        }
    }

    override fun equals(other: Any?): Boolean = other is IpAddress && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String =
        if (isIpv4) "${this[0]}.${this[1]}.${this[2]}.${this[3]}"
        else (0 until 8).joinToString(":") { ((this[2 * it] shl 8) or this[2 * it + 1]).toString(16) }

    companion object {
        /** From 4 (IPv4) or 16 (IPv6) bytes. */
        fun of(bytes: ByteArray): IpAddress {
            require(bytes.size == 4 || bytes.size == 16) { "an IP address has 4 or 16 bytes, got ${bytes.size}" }
            return IpAddress(bytes.copyOf())
        }

        /**
         * A canonical IP literal: dotted-decimal IPv4 with four parts in 0..255 and no leading zeros, or
         * an RFC 4291 IPv6 text (`::` compression, trailing dotted IPv4), optionally in brackets.
         * Anything else (a name, a zone id, an alternative IPv4 spelling) is null.
         */
        fun parse(text: String): IpAddress? {
            val t = if (text.length > 2 && text.startsWith('[') && text.endsWith(']')) text.substring(1, text.length - 1) else text
            return if (':' in t) parseIpv6(t) else parseCanonicalIpv4(t)
        }

        /**
         * How the platform resolver (`inet_aton`, and the WHATWG URL host parser) reads a host whose
         * labels are all numbers: one to four parts, each decimal, `0x` hex or leading-zero octal, the
         * last part filling the remaining bytes. `2130706433`, `0x7f.1`, `127.1` and `0177.0.0.1` all
         * mean 127.0.0.1. Null when [host] is not of that shape or its parts overflow.
         */
        fun parseLegacyIpv4(host: String): IpAddress? {
            val h = host.removeSuffix(".")
            if (h.isEmpty()) return null
            val parts = h.split('.')
            if (parts.size > 4) return null
            val values = parts.map { parseLegacyPart(it) ?: return null }
            for (i in 0 until values.size - 1) if (values[i] > 255) return null
            val last = values.last()
            val lastBytes = 4 - (values.size - 1)
            if (last >= (1L shl (8 * lastBytes))) return null
            val out = ByteArray(4)
            for (i in 0 until values.size - 1) out[i] = values[i].toByte()
            for (i in 0 until lastBytes) out[3 - i] = ((last shr (8 * i)) and 0xff).toByte()
            return IpAddress(out)
        }

        /** True when every label of [host] looks like a number (so a resolver treats it as an IPv4 literal). */
        internal fun looksNumeric(host: String): Boolean {
            val h = host.removeSuffix(".")
            if (h.isEmpty()) return false
            // WHATWG: a host whose last label is a number is parsed as IPv4, whatever the other labels are.
            val last = h.substringAfterLast('.')
            return last.isNotEmpty() && isNumberShaped(last)
        }

        private fun isNumberShaped(s: String): Boolean {
            val lower = s.lowercase()
            if (lower.startsWith("0x")) return lower.drop(2).all { it in '0'..'9' || it in 'a'..'f' }
            return lower.all { it in '0'..'9' }
        }

        private fun parseLegacyPart(s: String): Long? {
            if (s.isEmpty()) return null
            val lower = s.lowercase()
            val (digits, radix) = when {
                lower.startsWith("0x") -> lower.drop(2) to 16
                lower.length > 1 && lower.startsWith('0') -> lower.drop(1) to 8
                else -> lower to 10
            }
            if (digits.isEmpty()) return if (radix == 16) 0L else null
            if (digits.length > 12) return null
            return digits.toLongOrNull(radix)?.takeIf { it in 0..0xFFFF_FFFFL }
        }

        private fun parseCanonicalIpv4(t: String): IpAddress? {
            val parts = t.split('.')
            if (parts.size != 4) return null
            val out = ByteArray(4)
            for ((i, p) in parts.withIndex()) {
                if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
                if (p.length > 1 && p[0] == '0') return null
                val v = p.toInt()
                if (v > 255) return null
                out[i] = v.toByte()
            }
            return IpAddress(out)
        }

        private fun parseIpv6(t: String): IpAddress? {
            if ('%' in t) return null
            val doubleColon = t.indexOf("::")
            if (doubleColon >= 0 && t.indexOf("::", doubleColon + 1) >= 0) return null
            val head: List<String>
            val tail: List<String>
            if (doubleColon >= 0) {
                head = t.substring(0, doubleColon).let { if (it.isEmpty()) emptyList() else it.split(':') }
                tail = t.substring(doubleColon + 2).let { if (it.isEmpty()) emptyList() else it.split(':') }
            } else {
                head = t.split(':')
                tail = emptyList()
            }
            val groups = ArrayList<Int>(8)
            fun groupsOf(parts: List<String>, allowV4Last: Boolean): List<Int>? {
                val out = ArrayList<Int>()
                for ((i, p) in parts.withIndex()) {
                    if (i == parts.lastIndex && allowV4Last && '.' in p) {
                        val v4 = parseCanonicalIpv4(p) ?: return null
                        out += (v4[0] shl 8) or v4[1]
                        out += (v4[2] shl 8) or v4[3]
                        continue
                    }
                    if (p.isEmpty() || p.length > 4 || !p.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                    out += p.toInt(16)
                }
                return out
            }
            val h = groupsOf(head, allowV4Last = doubleColon < 0) ?: return null
            val tl = groupsOf(tail, allowV4Last = true) ?: return null
            if (doubleColon >= 0) {
                if (h.size + tl.size > 7) return null
                groups += h
                repeat(8 - h.size - tl.size) { groups += 0 }
                groups += tl
            } else {
                if (h.size != 8) return null
                groups += h
            }
            val out = ByteArray(16)
            for (i in 0 until 8) {
                out[2 * i] = (groups[i] shr 8).toByte()
                out[2 * i + 1] = groups[i].toByte()
            }
            return IpAddress(out)
        }
    }
}

/** An address block in CIDR form, e.g. `10.0.0.0/8` or `fc00::/7`. */
class IpNetwork(val address: IpAddress, val prefixLength: Int) {
    init {
        require(prefixLength in 0..(address.size * 8)) { "prefix /$prefixLength does not fit $address" }
    }

    /** Whether [ip] is in this block. An IPv4 block never contains an IPv6 address and vice versa. */
    operator fun contains(ip: IpAddress): Boolean {
        if (ip.size != address.size) return false
        var bits = prefixLength
        var i = 0
        while (bits >= 8) {
            if (ip[i] != address[i]) return false
            bits -= 8; i++
        }
        if (bits == 0) return true
        val mask = (0xff shl (8 - bits)) and 0xff
        return (ip[i] and mask) == (address[i] and mask)
    }

    override fun toString(): String = "$address/$prefixLength"

    override fun equals(other: Any?): Boolean = other is IpNetwork && other.address == address && other.prefixLength == prefixLength

    override fun hashCode(): Int = address.hashCode() * 31 + prefixLength

    companion object {
        /** `addr/len`, or a bare address (a single-address block). Throws [IllegalArgumentException] when malformed. */
        fun parse(text: String): IpNetwork {
            val t = text.trim()
            val slash = t.indexOf('/')
            val addrText = if (slash < 0) t else t.substring(0, slash)
            val address = IpAddress.parse(addrText) ?: throw IllegalArgumentException("not an IP network: $text")
            val len = if (slash < 0) address.size * 8 else t.substring(slash + 1).toIntOrNull()
                ?: throw IllegalArgumentException("not an IP network: $text")
            return IpNetwork(address, len)
        }
    }
}
