package neton.storage

import neton.storage.internal.guessMimeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MimeTypesTest {

    @Test
    fun `common extensions resolve correctly`() {
        assertEquals("image/jpeg", guessMimeType("photo.jpg"))
        assertEquals("image/jpeg", guessMimeType("photo.jpeg"))
        assertEquals("image/png", guessMimeType("logo.png"))
        assertEquals("application/pdf", guessMimeType("doc.pdf"))
        assertEquals("application/json", guessMimeType("data.json"))
        assertEquals("text/html", guessMimeType("index.html"))
        assertEquals("text/css", guessMimeType("style.css"))
        assertEquals("application/javascript", guessMimeType("app.js"))
        assertEquals("text/plain", guessMimeType("readme.txt"))
    }

    @Test
    fun `case insensitive extension`() {
        assertEquals("image/jpeg", guessMimeType("PHOTO.JPG"))
        assertEquals("image/png", guessMimeType("logo.PNG"))
    }

    @Test
    fun `nested path works`() {
        assertEquals("image/jpeg", guessMimeType("uploads/2026/02/avatar.jpg"))
    }

    @Test
    fun `no extension returns null`() {
        assertNull(guessMimeType("Makefile"))
        assertNull(guessMimeType("LICENSE"))
    }

    @Test
    fun `unknown extension returns null`() {
        assertNull(guessMimeType("data.xyz123"))
    }
}
