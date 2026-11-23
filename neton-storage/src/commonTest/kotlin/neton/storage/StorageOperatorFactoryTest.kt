package neton.storage

import kotlin.test.*

class StorageOperatorFactoryTest {
    @Test fun createsLocalOperatorWithoutAnHttpClient() {
        val operator = StorageOperatorFactory.create(SourceConfig(name = "images", basePath = "/tmp/images", baseUrl = "https://images.example.com"))
        assertEquals("local", operator.scheme)
        assertEquals("images", operator.name)
        assertEquals("https://images.example.com", operator.baseUrl)
    }
    @Test fun rejectsUnsupportedBackendsAndMissingBorrowedClient() {
        assertFailsWith<IllegalStateException> { StorageOperatorFactory.create(SourceConfig(type = "oss")) }
        assertFailsWith<IllegalArgumentException> { StorageOperatorFactory.create(SourceConfig(type = "s3")) }
    }
}
