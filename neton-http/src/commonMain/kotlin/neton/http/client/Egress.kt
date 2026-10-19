package neton.http.client

import neton.core.config.ConfigLoader

/**
 * The operator's egress allowlist: the one place a deployment says which special-purpose addresses (a
 * self-hosted model on 10.x, a local stub, a TUN proxy's 198.18.0.0/15 fake answers) and which extra ports
 * guarded outbound requests may reach. `[egress]` in `application.conf`:
 *
 * ```toml
 * [egress]
 * allowed_ports = "80,443"              # default 80,443; a guarded URL on another port is refused
 * allowed_hosts = ""                    # exact host names / IP literals admitted whatever they resolve to, any port
 * allowed_networks = ""                 # CIDR blocks admitted although special-purpose (ports still checked)
 * ```
 *
 * Lists are comma separated (the framework's TOML parser has no arrays). Environment overrides follow the
 * application config rule: `NETON_EGRESS__ALLOWED_NETWORKS=10.20.0.0/16`. A malformed port or CIDR fails
 * startup instead of silently falling back to the default.
 *
 * It is deploy configuration on purpose: nothing an admin can edit at runtime (a gateway channel, a
 * payment endpoint, a webhook) may widen it. Every guarded client of the process takes its
 * [OutboundAddressPolicy] from here through [GuardedHttpClientProvider]; no module keeps an allowlist of
 * its own.
 */
class EgressSettings(
    val allowedPorts: Set<Int> = OutboundAddressPolicy.DEFAULT_PORTS,
    val allowedHosts: Set<String> = emptySet(),
    val allowedNetworks: List<IpNetwork> = emptyList(),
) {
    /** The address policy every guarded client of the process uses. */
    val policy: OutboundAddressPolicy = OutboundAddressPolicy(allowedPorts, allowedHosts, allowedNetworks)

    /**
     * The same allowlist for SMTP servers set in the back office ([neton.http.mail.SmtpTransport.send]):
     * ports [SMTP_PORTS] instead of [allowedPorts] (hosts in [allowedHosts] are admitted on any port).
     */
    val smtpPolicy: OutboundAddressPolicy = OutboundAddressPolicy(SMTP_PORTS, allowedHosts, allowedNetworks)

    /** True when something special-purpose is reachable: applications log this loudly at startup. */
    val isRelaxed: Boolean get() = policy.isRelaxed

    /** One line for the startup log. */
    fun describe(): String =
        "allowed_ports=${allowedPorts.sorted().joinToString(",")} allowed_hosts=${policy.allowedHosts.joinToString(",")} " +
            "allowed_networks=${allowedNetworks.joinToString(",")}"

    override fun toString(): String = "EgressSettings(${describe()})"

    companion object {
        const val SECTION = "egress"

        /** SMTP relay (25), implicit-TLS submission (465), submission (587) and the common alternative 2525. */
        val SMTP_PORTS: Set<Int> = setOf(25, 465, 587, 2525)

        /** `[egress]` of the application config (`application.conf` + environment file + overrides) for [args]. */
        fun load(args: Array<String>): EgressSettings = fromApplicationConfig(
            ConfigLoader.loadApplicationConfig(environment = ConfigLoader.resolveEnvironment(args), args = args),
        )

        /** `[egress]` of an already loaded application config; absent section → the strict default. */
        fun fromApplicationConfig(conf: Map<String, Any?>?): EgressSettings {
            @Suppress("UNCHECKED_CAST")
            val section = ConfigLoader.getConfigValue(conf, SECTION) as? Map<String, Any?> ?: return EgressSettings()
            fun list(key: String): List<String> =
                section[key]?.toString()?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            val ports = if (section.containsKey("allowed_ports")) {
                list("allowed_ports").map { p ->
                    p.toIntOrNull()?.takeIf { it in 1..65535 }
                        ?: throw IllegalArgumentException("$SECTION.allowed_ports: '$p' is not a port")
                }.toSet()
            } else OutboundAddressPolicy.DEFAULT_PORTS
            val networks = list("allowed_networks").map { n ->
                try {
                    IpNetwork.parse(n)
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("$SECTION.allowed_networks: '$n' is not a CIDR block", e)
                }
            }
            return EgressSettings(ports, list("allowed_hosts").toSet(), networks)
        }
    }
}

/**
 * Builds the **guarded** outbound clients: every client it creates carries the operator's
 * [OutboundAddressPolicy] ([EgressSettings]), so each connection is resolved once, checked (public internet,
 * allowed port, or named by the operator allowlist) and dialled at exactly the checked address — through a
 * proxy too ([HttpClientConfig.proxyUrl]: the proxy endpoint is checked the same way and only ever told the
 * checked IP).
 *
 * The rule for which client a request uses:
 *  - the URL comes from a user, from fetched content, or from **admin runtime data** (a database row, a
 *    setting editable in the admin UI: gateway channels, payment / SMS / push endpoints set in the
 *    back office, webhook targets) → a client from here;
 *  - the URL is fixed in deploy configuration (a `.conf` file, an environment variable, a constant) → the
 *    application's normal [HttpClient] / [HttpClientProvider].
 *
 * Callers configure timeouts, proxy, connection limits as with [HttpClientProvider]; the address policy is
 * not theirs to choose — setting one is refused. A [HttpClientConfig.hostResolver] may be set (tests). The
 * caller owns and closes what it creates.
 */
class GuardedHttpClientProvider(
    val settings: EgressSettings,
    private val provider: HttpClientProvider,
) {
    val policy: OutboundAddressPolicy get() = settings.policy

    fun create(block: HttpClientConfig.() -> Unit = {}): HttpClient = provider.create {
        block()
        require(addressPolicy == null) {
            "a guarded client's address policy is the operator egress policy; do not set addressPolicy"
        }
        addressPolicy = settings.policy
    }
}
