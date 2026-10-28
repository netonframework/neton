package neton.storage

import neton.storage.internal.SourceConfigParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SourceConfigParserTest {

    @Test
    fun wrongFieldTypesFailWithoutRenderingValues() {
        for (field in listOf("name", "type", "baseUrl", "basePath", "endpoint", "region", "bucket", "accessKey", "secretKey", "pathStyle")) {
            val raw = mapOf("sources" to listOf(mapOf("name" to "default", field to listOf("sensitive-value"))))
            val error = assertFailsWith<IllegalStateException> { SourceConfigParser.parseSourceConfigs(raw) }
            assertTrue(!error.message.orEmpty().contains("sensitive-value"))
        }
    }

    @Test
    fun explicitDefaultsOverrideEveryFieldAndUnsetFieldsInherit() {
        val file = SourceConfig(type = "s3", baseUrl = "https://cdn.test", basePath = "/data",
            endpoint = "https://s3.test", region = "region", bucket = "bucket",
            accessKey = "access", secretKey = "secret", pathStyle = true)
        val inherited = StorageConfig().apply { source("default") {} }
        assertEquals(file, SourceConfigParser.mergeSources(emptyList(), listOf(file), inherited.overrides).single())
        val explicit = StorageConfig().apply {
            source("default") {
                type = "local"; baseUrl = ""; basePath = "./uploads"
                endpoint = ""; region = ""; bucket = ""
                accessKey = ""; secretKey = ""; pathStyle = false
            }
        }
        assertEquals(SourceConfig(), SourceConfigParser.mergeSources(emptyList(), listOf(file), explicit.overrides).single())
        val partial = SourceOverrides(basePath = "./uploads", pathStyle = false)
        assertEquals(file.copy(basePath = "./uploads", pathStyle = false),
            SourceConfigParser.mergeSources(emptyList(), listOf(file), listOf(partial)).single())
        partial.pathStyle = null
        assertTrue(SourceConfigParser.mergeSources(emptyList(), listOf(file), listOf(partial)).single().pathStyle)
    }

    @Test
    fun overrideCopyAndMissingFileAndDuplicateSemantics() {
        var captured: SourceOverrides? = null
        val config = StorageConfig().apply { source("default") { captured = this; baseUrl = "" } }
        captured!!.baseUrl = "changed-after-block"
        assertEquals(SourceConfig(), StorageComponent.resolveSources(null, config.copy().sources, config.copy().overrides).single())
        assertFailsWith<IllegalStateException> {
            StorageComponent.resolveSources(null, listOf(SourceConfig()), config.overrides)
        }
        assertFailsWith<IllegalStateException> {
            StorageComponent.resolveSources(null, emptyList(), config.overrides + config.overrides)
        }
        assertFailsWith<IllegalStateException> {
            StorageComponent.resolveSources(null, emptyList(), listOf(SourceOverrides(name = "")))
        }
        assertFailsWith<IllegalStateException> {
            StorageComponent.resolveSources(emptyMap(), emptyList(), config.overrides)
        }
        val file = listOf(SourceConfig(type = "s3", pathStyle = true))
        assertEquals(SourceConfig(), SourceConfigParser.mergeSources(listOf(SourceConfig()), file).single())
    }

    @Test
    fun overrideCredentialsAreRedactedIncludingCopies() {
        val patch = SourceOverrides(accessKey = "sensitive-access", secretKey = "sensitive-secret")
        for (rendered in listOf(patch.toString(), patch.copy().toString(),
            StorageConfig(overrides = mutableListOf(patch)).copy().toString())) {
            assertTrue(!rendered.contains("sensitive-"))
        }
    }

    @Test
    fun invalidConfigurationDoesNotFallbackEvenWhenDslIsValid() {
        for (raw in listOf(emptyMap(), mapOf("sources" to emptyList<Any>()),
            mapOf("sources" to listOf(mapOf("name" to ""))),
            mapOf("sources" to listOf(mapOf("name" to "default"), mapOf("name" to "default"))))) {
            assertFailsWith<IllegalStateException> { StorageComponent.resolveSources(raw, listOf(SourceConfig())) }
        }
        assertEquals("local", StorageComponent.resolveSources(null, emptyList()).single().type)
        assertEquals("default", StorageComponent.resolveSources(null, listOf(SourceConfig())).single().name)
    }

    @Test
    fun duplicatesMustNotDisappearDuringMerge() {
        val duplicate = listOf(SourceConfig(), SourceConfig())
        assertFailsWith<IllegalStateException> { SourceConfigParser.mergeSources(duplicate, emptyList()) }
        assertFailsWith<IllegalStateException> { SourceConfigParser.mergeSources(emptyList(), duplicate) }
        assertEquals(1, SourceConfigParser.mergeSources(listOf(SourceConfig()), listOf(SourceConfig())).size)
    }

    @Test
    fun credentialsAreNotRendered() {
        val source = SourceConfig(accessKey = "access-test-secret", secretKey = "private-test-secret")
        for (rendered in listOf(source.toString(), source.copy().toString(), StorageConfig(mutableListOf(source)).toString())) {
            assertTrue(!rendered.contains("access-test-secret"))
            assertTrue(!rendered.contains("private-test-secret"))
        }
    }

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
