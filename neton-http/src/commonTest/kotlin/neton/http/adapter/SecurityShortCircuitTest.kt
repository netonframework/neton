package neton.http.adapter

import neton.core.interfaces.RouteGroupSecurityConfig
import neton.core.interfaces.RouteGroupSecurityConfigs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `routing { }` binds a [RouteGroupSecurityConfigs] whenever an application.conf
 * exists, whether or not it declares any groups. Treating the binding itself as
 * "security is installed" put every request of every application through the
 * security path, which reads the whole header map.
 *
 * The distinction that matters is whether anything is actually configured.
 */
class SecurityShortCircuitTest {

    private fun installed(configs: RouteGroupSecurityConfigs?): Boolean =
        configs != null && configs.configs.isNotEmpty()

    @Test
    fun noBindingAtAllIsNotInstalled() {
        assertFalse(installed(null))
    }

    @Test
    fun anEmptyGroupMapIsNotInstalled() {
        assertFalse(
            installed(RouteGroupSecurityConfigs(emptyMap())),
            "an application.conf with no [[groups]] must not switch security on",
        )
    }

    @Test
    fun aConfiguredGroupIsInstalled() {
        assertTrue(
            installed(
                RouteGroupSecurityConfigs(
                    mapOf("admin" to RouteGroupSecurityConfig(requireAuth = true, allowAnonymous = emptySet())),
                ),
            ),
        )
    }

    @Test
    fun aGroupThatOnlyAllowsAnonymousStillCounts() {
        // Declaring a group at all means the pipeline has something to enforce,
        // even when that group requires no auth: allowAnonymous is a decision.
        assertTrue(
            installed(
                RouteGroupSecurityConfigs(
                    mapOf("app" to RouteGroupSecurityConfig(requireAuth = false, allowAnonymous = setOf("/health"))),
                ),
            ),
        )
    }
}
