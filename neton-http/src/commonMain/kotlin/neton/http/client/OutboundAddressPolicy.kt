package neton.http.client

/**
 * Resolves a host name to the addresses an outbound client may dial.
 *
 * The built-in client uses the platform resolver when [HttpClientConfig.hostResolver] is not set;
 * tests set one to make a name resolve to whatever they need (a private address, an answer that
 * changes between lookups).
 *
 * @throws HttpClientException with [HttpClientError.TargetRejected] ([TargetRejection.RESOLVE]) or
 *   any other exception when the name does not resolve; the client reports both as RESOLVE.
 */
fun interface HostResolver {
    suspend fun resolve(host: String): List<IpAddress>
}

/** Why an [OutboundAddressPolicy] refused a request target. */
enum class TargetRejection {
    /** Not `http` / `https`. */
    SCHEME,

    /** The port is not in [OutboundAddressPolicy.allowedPorts]. */
    PORT,

    /** The host is, or resolves to, an address outside the public internet, or is a non-canonical IP literal. */
    ADDRESS,

    /** The host name did not resolve. */
    RESOLVE,
}

/**
 * Who an outbound client may connect to when the URL is not the application's own (a user-supplied
 * link, an image in fetched HTML, a redirect `Location`): the public internet only.
 *
 * Set on [HttpClientConfig.addressPolicy]. The client then, for **every new connection** (so for every
 * redirect hop a caller follows to another authority as well):
 *
 * 1. checks the scheme (`http` / `https`) and the port ([allowedPorts]);
 * 2. takes an IP literal host as the address (refusing alternative IPv4 spellings such as `2130706433`,
 *    `0x7f.1`, `127.1`, `0177.0.0.1` that the platform resolver would quietly turn into an address),
 *    or resolves a name;
 * 3. refuses the target when **any** address is special-purpose ([isPublic] false) — a name with one
 *    public and one private answer is refused, not trimmed;
 * 4. dials exactly the addresses it checked, as literals. Nothing resolves the name a second time, so an
 *    answer that changes between the check and the connect (DNS rebinding) cannot move the connection.
 *    TLS still verifies the certificate against, and sends SNI for, the URL's host name.
 *
 * A refused target fails the request with [HttpClientError.TargetRejected]; no packet leaves for it.
 *
 * The escape hatch is explicit and narrow, meant for local development (a stub origin on the loopback,
 * a TUN proxy that answers every name with a 198.18.0.0/15 fake address):
 *  - [allowedHosts]: exact host names (or IP literals) admitted whatever they resolve to and on any port;
 *  - [allowedNetworks]: address blocks admitted although special-purpose (ports are still checked).
 * Both are empty by default; [isRelaxed] tells an application to log loudly that they are not.
 *
 * Mutually exclusive with [HttpClientConfig.proxyUrl]: through a proxy the proxy resolves and dials, and
 * nothing here could hold it to the checked address.
 */
class OutboundAddressPolicy(
    val allowedPorts: Set<Int> = DEFAULT_PORTS,
    allowedHosts: Set<String> = emptySet(),
    val allowedNetworks: List<IpNetwork> = emptyList(),
) {
    val allowedHosts: Set<String> = allowedHosts.map { normalizeHost(it) }.filter { it.isNotEmpty() }.toSet()

    init {
        require(allowedPorts.all { it in 1..65535 }) { "allowedPorts must be in 1..65535: $allowedPorts" }
    }

    /** True when an allowlist is configured: something special-purpose is reachable. */
    val isRelaxed: Boolean get() = allowedHosts.isNotEmpty() || allowedNetworks.isNotEmpty()

    /**
     * The addresses [scheme]://[host]:[port] may be dialled at, in the resolver's order; throws
     * [HttpClientException] with [HttpClientError.TargetRejected] otherwise. [host] may carry IPv6
     * brackets.
     */
    suspend fun admit(scheme: String, host: String, port: Int, resolver: HostResolver): List<IpAddress> {
        val s = scheme.lowercase()
        if (s != "http" && s != "https") reject(TargetRejection.SCHEME, "scheme '$scheme' is not http/https")
        val name = normalizeHost(host)
        if (name.isEmpty()) reject(TargetRejection.ADDRESS, "empty host")
        val hostAllowed = name in allowedHosts
        if (!hostAllowed && port !in allowedPorts) reject(TargetRejection.PORT, "port $port is not allowed for $name")

        val literal = IpAddress.parse(name)
        val addresses: List<IpAddress> = when {
            literal != null -> listOf(literal)
            ':' in name || '%' in name -> reject(TargetRejection.ADDRESS, "malformed IPv6 literal '$name'")
            IpAddress.looksNumeric(name) -> reject(TargetRejection.ADDRESS, "non-canonical IP literal '$name'")
            else -> resolveName(name, resolver)
        }
        if (addresses.isEmpty()) reject(TargetRejection.RESOLVE, "'$name' resolved to no address")
        if (!hostAllowed) {
            for (a in addresses) {
                if (!isPublic(a) && allowedNetworks.none { a in it }) {
                    reject(TargetRejection.ADDRESS, "'$name' resolves to non-public address $a")
                }
            }
        }
        return addresses
    }

    private suspend fun resolveName(name: String, resolver: HostResolver): List<IpAddress> = try {
        resolver.resolve(name)
    } catch (e: HttpClientException) {
        if (e.error is HttpClientError.TargetRejected) throw e
        reject(TargetRejection.RESOLVE, "cannot resolve '$name': ${e.message}")
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        reject(TargetRejection.RESOLVE, "cannot resolve '$name': ${e.message}")
    }

    override fun toString(): String =
        "OutboundAddressPolicy(allowedPorts=$allowedPorts, allowedHosts=$allowedHosts, allowedNetworks=$allowedNetworks)"

    companion object {
        val DEFAULT_PORTS: Set<Int> = setOf(80, 443)

        /**
         * Special-purpose blocks (IANA IPv4/IPv6 special-purpose registries) an untrusted fetch must not
         * reach: this host, private networks, CGNAT, loopback, link-local (cloud metadata at
         * 169.254.169.254), protocol assignments, documentation, benchmarking (also where TUN proxies hand
         * out fake addresses), multicast, reserved, broadcast; for IPv6 also unique-local, discard,
         * site-local and the IETF protocol block (Teredo, ORCHID).
         */
        val BLOCKED_NETWORKS: List<IpNetwork> = listOf(
            "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12",
            "192.0.0.0/24", "192.0.2.0/24", "192.88.99.0/24", "192.168.0.0/16", "198.18.0.0/15",
            "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/4", "240.0.0.0/4", "255.255.255.255/32",
            "::/128", "::1/128", "::/96", "64:ff9b:1::/48", "100::/64", "2001::/23", "2001:db8::/32",
            "fc00::/7", "fe80::/10", "fec0::/10", "ff00::/8",
        ).map { IpNetwork.parse(it) }

        /**
         * Whether [address] is on the public internet. An IPv6 address that carries an IPv4 one
         * (IPv4-mapped `::ffff:0:0/96`, NAT64 `64:ff9b::/96`, 6to4 `2002::/16`) is judged by that IPv4
         * address, since that is where the packets go.
         */
        fun isPublic(address: IpAddress): Boolean {
            val embedded = address.embeddedIpv4()
            if (embedded != null) {
                // ::a.b.c.d (IPv4-compatible) is deprecated and in ::/96: refused as a block, not by its IPv4.
                if (address[10] == 0 && address[11] == 0 && (0 until 10).all { address[it] == 0 }) return false
                return isPublic(embedded)
            }
            return BLOCKED_NETWORKS.none { address in it }
        }

        /** Lower case, IPv6 brackets and one trailing dot removed. */
        internal fun normalizeHost(host: String): String {
            var h = host.trim().lowercase()
            if (h.length > 2 && h.startsWith('[') && h.endsWith(']')) h = h.substring(1, h.length - 1)
            return h.removeSuffix(".")
        }

        private fun reject(reason: TargetRejection, message: String): Nothing =
            throw HttpClientException(HttpClientError.TargetRejected(reason, message))
    }
}
