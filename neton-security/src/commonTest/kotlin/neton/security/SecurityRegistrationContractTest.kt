package neton.security

import kotlinx.coroutines.test.runTest
import neton.core.component.NetonContext
import neton.core.config.NetonConfigRegistry
import neton.core.config.NetonConfigurer
import neton.core.config.NetonConfigurers
import neton.core.interfaces.Authenticator
import neton.core.interfaces.Identity
import neton.core.interfaces.RequestContext
import neton.core.interfaces.SecurityBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Registration slots are claimed once. Assembly order never decides which authenticator,
 * guard or permission evaluator wins; replacing one must be declared with replace*.
 */
class SecurityRegistrationContractTest {

    private class NamedAuthenticator(override val name: String) : Authenticator {
        override suspend fun authenticate(context: RequestContext): Identity? = null
    }

    private class FirstConfig(private val auth: Authenticator) : NetonConfigurer<SecurityBuilder> {
        override fun configure(ctx: NetonContext, target: SecurityBuilder) {
            target.registerAuthenticator(auth)
            target.bindDefaultGuard()
        }
    }

    private class SecondConfig(private val auth: Authenticator) : NetonConfigurer<SecurityBuilder> {
        override fun configure(ctx: NetonContext, target: SecurityBuilder) {
            target.registerAuthenticator(auth)
        }
    }

    private class ReplacingConfig(
        private val replaces: String,
        private val auth: Authenticator,
    ) : NetonConfigurer<SecurityBuilder> {
        override fun configure(ctx: NetonContext, target: SecurityBuilder) {
            target.replaceDefaultAuthenticator(replaces, auth)
        }
    }

    private class GroupConfig(private val group: String, private val auth: Authenticator) :
        NetonConfigurer<SecurityBuilder> {
        override fun configure(ctx: NetonContext, target: SecurityBuilder) {
            target.setGroupAuthenticator(group, auth)
        }
    }

    private fun registryOf(vararg configurers: NetonConfigurer<SecurityBuilder>) = object : NetonConfigRegistry {
        override val securityConfigurers: List<NetonConfigurer<SecurityBuilder>> = configurers.toList()
    }

    private suspend fun configureWith(registry: NetonConfigRegistry, dsl: SecurityBuilder.() -> Unit = {}): SecurityBuilder {
        val ctx = NetonContext(emptyArray())
        ctx.bind(NetonConfigRegistry::class, registry)
        val builder = SecurityComponent.defaultConfig()
        builder.dsl()
        SecurityComponent.init(ctx, builder)
        SecurityComponent.configure(ctx)
        return builder
    }

    private val firstSource = FirstConfig::class.qualifiedName!!
    private val secondSource = SecondConfig::class.qualifiedName!!

    @Test
    fun sameOrderSameSlot_failsNamingBothSources() = runTest {
        val ex = assertFailsWith<SecurityRegistrationException> {
            configureWith(
                registryOf(
                    NetonConfigurers.ordered(0, FirstConfig(NamedAuthenticator("realm"))),
                    NetonConfigurers.ordered(0, SecondConfig(NamedAuthenticator("legacy"))),
                ),
            )
        }
        assertEquals(SecurityRegistrationException.CONFLICT, ex.code)
        assertEquals("authenticator[default]", ex.slot)
        val message = ex.message!!
        assertTrue(message.startsWith("security.registration.conflict slot=authenticator[default]"), message)
        assertTrue("realm@$firstSource(order=0)" in message, message)
        assertTrue("legacy@$secondSource(order=0)" in message, message)
    }

    @Test
    fun higherOrderWithoutReplace_stillFails() = runTest {
        // A later order is not a declaration: implicit override by order is exactly the bug.
        val ex = assertFailsWith<SecurityRegistrationException> {
            configureWith(
                registryOf(
                    NetonConfigurers.ordered(0, FirstConfig(NamedAuthenticator("realm"))),
                    NetonConfigurers.ordered(5, SecondConfig(NamedAuthenticator("alias"))),
                ),
            )
        }
        assertEquals(SecurityRegistrationException.CONFLICT, ex.code)
        assertTrue("alias@$secondSource(order=5)" in ex.message!!)
    }

    @Test
    fun declaredReplace_winsAndIsReported() = runTest {
        val replacement = NamedAuthenticator("realm-alias")
        val builder = configureWith(
            registryOf(
                NetonConfigurers.ordered(0, FirstConfig(NamedAuthenticator("realm"))),
                NetonConfigurers.ordered(1, ReplacingConfig(firstSource, replacement)),
            ),
        )
        assertSame(replacement, builder.build().defaultAuthenticator)
        val default = builder.getGroupConfigSummary().first { it.group == null }
        assertEquals("realm-alias", default.authenticator)
        assertEquals(ReplacingConfig::class.qualifiedName, default.authenticatorSource)
        assertEquals(firstSource, default.guardSource)
    }

    @Test
    fun replaceNamingWrongSource_fails() = runTest {
        val ex = assertFailsWith<SecurityRegistrationException> {
            configureWith(
                registryOf(
                    NetonConfigurers.ordered(0, FirstConfig(NamedAuthenticator("realm"))),
                    NetonConfigurers.ordered(1, ReplacingConfig("config.SomethingElse", NamedAuthenticator("x"))),
                ),
            )
        }
        assertEquals(SecurityRegistrationException.REPLACE_TARGET_MISMATCH, ex.code)
    }

    @Test
    fun replaceBeforeTargetRegistered_fails() = runTest {
        // Same order, replacer listed first: the target has not registered yet, so nothing to replace.
        val ex = assertFailsWith<SecurityRegistrationException> {
            configureWith(
                registryOf(
                    NetonConfigurers.ordered(0, ReplacingConfig(firstSource, NamedAuthenticator("x"))),
                    NetonConfigurers.ordered(0, FirstConfig(NamedAuthenticator("realm"))),
                ),
            )
        }
        assertEquals(SecurityRegistrationException.REPLACE_TARGET_MISSING, ex.code)
    }

    @Test
    fun dslAndConfigurerOnSameSlot_fails() = runTest {
        val ex = assertFailsWith<SecurityRegistrationException> {
            configureWith(registryOf(NetonConfigurers.ordered(0, FirstConfig(NamedAuthenticator("realm"))))) {
                setPermissionEvaluator { _, _, _ -> true }
                registerAuthenticator(NamedAuthenticator("dsl"))
            }
        }
        assertEquals("authenticator[default]", ex.slot)
        assertTrue("dsl@${SecurityBuilderImpl.DSL_SOURCE}" in ex.message!!, ex.message)
    }

    @Test
    fun permissionEvaluatorSetTwice_fails() = runTest {
        val ex = assertFailsWith<SecurityRegistrationException> {
            configureWith(registryOf()) {
                setPermissionEvaluator { _, _, _ -> true }
                setPermissionEvaluator { _, _, _ -> false }
            }
        }
        assertEquals("permissionEvaluator", ex.slot)
    }

    @Test
    fun guardSetTwice_fails() = runTest {
        val ex = assertFailsWith<SecurityRegistrationException> {
            configureWith(registryOf()) {
                bindDefaultGuard()
                bindAnonymousGuard()
            }
        }
        assertEquals("guard[default]", ex.slot)
    }

    @Test
    fun distinctGroups_coexistAndAreAttributed() = runTest {
        val builder = configureWith(
            registryOf(
                NetonConfigurers.ordered(0, FirstConfig(NamedAuthenticator("realm"))),
                NetonConfigurers.ordered(10, GroupConfig("gateway", NamedAuthenticator("gateway-token"))),
            ),
        )
        val gateway = builder.getGroupConfigSummary().first { it.group == "gateway" }
        assertEquals("gateway-token", gateway.authenticator)
        assertEquals(GroupConfig::class.qualifiedName, gateway.authenticatorSource)
        assertEquals("gateway-token", builder.build().getAuthenticatorByGroup!!("gateway")!!.name)
        assertEquals("realm", builder.build().getAuthenticatorByGroup!!("admin")!!.name)
    }

    @Test
    fun sameGroupTwice_fails() = runTest {
        val ex = assertFailsWith<SecurityRegistrationException> {
            configureWith(
                registryOf(
                    NetonConfigurers.ordered(10, GroupConfig("gateway", NamedAuthenticator("a"))),
                    NetonConfigurers.ordered(10, GroupConfig("gateway", NamedAuthenticator("b"))),
                ),
            )
        }
        assertEquals("authenticator[gateway]", ex.slot)
    }

    @Test
    fun legacyRegistry_rejectsSecondRegistration() {
        assertFailsWith<SecurityRegistrationException> {
            security {
                registerAuthenticator(AnonymousAuthenticator())
                registerAuthenticator(AnonymousAuthenticator())
            }
        }
    }
}
