package neton.storage

import neton.storage.internal.SourceConfigParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SourceConfigParserTest {

    @Test
    fun `missing sources fails`() {
        assertFailsWith<IllegalStateException> {
            SourceConfigParser.parseSourceConfigs(emptyMap())
        }.also { assertTrue(it.message!!.contains("missing [[sources]]")) }
    }

    @Test
    fun `empty sources list fails`() {
        assertFailsWith<IllegalStateException> {
            SourceConfigParser.parseSourceConfigs(mapOf("sources" to emptyList<Any>()))
        }.also { assertTrue(it.message!!.contains("is empty")) }
    }

    @Test
    fun `missing name fails`() {
        assertFailsWith<IllegalStateException> {
            SourceConfigParser.parseSourceConfigs(mapOf("sources" to listOf(mapOf("type" to "local"))))
        }.also { assertTrue(it.message!!.contains("missing 'name'")) }
    }

    @Test
    fun `blank name fails`() {
        assertFailsWith<IllegalStateException> {
            SourceConfigParser.parseSourceConfigs(mapOf("sources" to listOf(mapOf("name" to ""))))
        }.also { assertTrue(it.message!!.contains("cannot be blank")) }
    }

    @Test
    fun `duplicate name fails validation`() {
        val sources = listOf(
            SourceConfig(name = "default"),
            SourceConfig(name = "default")
        )
        assertFailsWith<IllegalStateException> {
            SourceConfigParser.validateSources(sources)
        }.also { assertTrue(it.message!!.contains("duplicate")) }
    }

    @Test
    fun `missing default fails validation`() {
        val sources = listOf(SourceConfig(name = "other"))
        assertFailsWith<IllegalStateException> {
            SourceConfigParser.validateSources(sources)
        }.also { assertTrue(it.message!!.contains("missing source with name='default'")) }
    }

    @Test
    fun `valid parse succeeds`() {
        val raw = mapOf(
            "sources" to listOf(
                mapOf("name" to "default", "type" to "local", "basePath" to "/tmp"),
                mapOf(
                    "name" to "oss",
                    "type" to "s3",
                    "endpoint" to "https://s3.amazonaws.com",
                    "region" to "us-east-1",
                    "bucket" to "b",
                    "accessKey" to "ak",
                    "secretKey" to "sk"
                )
            )
        )
        val sources = SourceConfigParser.parseSourceConfigs(raw)
        assertEquals(2, sources.size)
        assertEquals("default", sources[0].name)
        assertEquals("local", sources[0].type)
        assertEquals("/tmp", sources[0].basePath)
        assertEquals("oss", sources[1].name)
        assertEquals("s3", sources[1].type)
        assertEquals("us-east-1", sources[1].region)
    }

    @Test
    fun `merge field-level override`() {
        val dsl = listOf(SourceConfig(name = "default", basePath = "/data/uploads"))
        val file = listOf(SourceConfig(name = "default", type = "local", basePath = "/var/uploads"))
        val merged = SourceConfigParser.mergeSources(dsl, file)
        assertEquals(1, merged.size)
        assertEquals("local", merged[0].type)
        assertEquals("/data/uploads", merged[0].basePath)
    }

    @Test
    fun `merge preserves file-only sources`() {
        val dsl = listOf(SourceConfig(name = "default", basePath = "/data"))
        val file = listOf(
            SourceConfig(name = "default", basePath = "/var"),
            SourceConfig(name = "backup", type = "s3", endpoint = "https://s3.example.com")
        )
        val merged = SourceConfigParser.mergeSources(dsl, file)
        assertEquals(2, merged.size)
        assertEquals("backup", merged[1].name)
        assertEquals("s3", merged[1].type)
    }

    @Test
    fun `merge appends dsl-only sources`() {
        val dsl = listOf(
            SourceConfig(name = "default"),
            SourceConfig(name = "extra", type = "s3", endpoint = "https://extra.example.com")
        )
        val file = listOf(SourceConfig(name = "default", basePath = "/var"))
        val merged = SourceConfigParser.mergeSources(dsl, file)
        assertEquals(2, merged.size)
        assertEquals("extra", merged[1].name)
    }
}
