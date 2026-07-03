package neton.database.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * STD-1 contract: DatabaseConfig fails fast on invalid config and never silently
 * falls back to MEMORY or a hardcoded default DB.
 */
class DatabaseConfigTest {

    @Test
    fun unknownDriverFailsFast() {
        val e = assertFailsWith<IllegalArgumentException> {
            DatabaseConfig.fromMap(mapOf("driver" to "ORACLE", "uri" to "oracle://x"))
        }
        assertTrue("Unknown database driver" in (e.message ?: ""))
    }

    @Test
    fun missingDriverFailsFastNoMemoryDefault() {
        assertFailsWith<IllegalArgumentException> {
            DatabaseConfig.fromMap(mapOf("uri" to "postgresql://u:p@h:5432/db"))
        }
    }

    @Test
    fun blankDriverFailsFast() {
        assertFailsWith<IllegalArgumentException> {
            DatabaseConfig.fromMap(mapOf("driver" to "  ", "uri" to "x"))
        }
    }

    @Test
    fun missingUriForNonMemoryDriverFailsFast() {
        val e = assertFailsWith<IllegalArgumentException> {
            DatabaseConfig.fromMap(mapOf("driver" to "POSTGRESQL"))
        }
        assertTrue("missing required 'uri'" in (e.message ?: ""))
    }

    @Test
    fun blankUriForNonMemoryDriverFailsFast() {
        assertFailsWith<IllegalArgumentException> {
            DatabaseConfig.fromMap(mapOf("driver" to "SQLITE", "uri" to ""))
        }
    }

    @Test
    fun memoryDriverAllowsOmittedUri() {
        val cfg = DatabaseConfig.fromMap(mapOf("driver" to "MEMORY"))
        assertEquals(DatabaseDriver.MEMORY, cfg.driver)
        assertEquals("memory://in-memory", cfg.uri)
    }

    @Test
    fun typoedDriverDoesNotSilentlyBecomeMemory() {
        assertFailsWith<IllegalArgumentException> {
            DatabaseConfig.fromMap(mapOf("driver" to "MEMROY", "uri" to "x"))
        }
    }

    @Test
    fun validPostgresConfigParses() {
        val cfg = DatabaseConfig.fromMap(mapOf(
            "driver" to "POSTGRESQL",
            "uri" to "postgresql://u:p@localhost:5432/app",
        ))
        assertEquals(DatabaseDriver.POSTGRESQL, cfg.driver)
        assertTrue(cfg.validate().isEmpty(), "valid config should have no validation errors")
    }

    @Test
    fun malformedUriSurfacesAsValidationError() {
        val cfg = DatabaseConfig.fromMap(mapOf(
            "driver" to "POSTGRESQL",
            "uri" to "not-a-valid-postgres-uri",
        ))
        assertTrue(cfg.validate().isNotEmpty(), "malformed uri must produce a validation error")
    }

    @Test
    fun queryOptionsAreParsedNotDropped() {
        val info = PostgresUriParser.parse("postgresql://u:p@h:5432/db?sslmode=require&pool_size=20")
        assertEquals("require", info.options["sslmode"])
        assertEquals("20", info.options["pool_size"])
    }

    @Test
    fun noQueryOptionsYieldsEmptyMap() {
        val info = PostgresUriParser.parse("postgresql://u:p@h:5432/db")
        assertTrue(info.options.isEmpty())
    }
}
