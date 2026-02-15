package neton.storage

import neton.storage.internal.PathUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PathUtilsTest {

    @Test
    fun `path traversal blocked`() {
        assertFailsWith<IllegalArgumentException> {
            PathUtils.validatePath("../etc/passwd")
        }
        assertFailsWith<IllegalArgumentException> {
            PathUtils.validatePath("foo/../../bar")
        }
    }

    @Test
    fun `absolute path blocked`() {
        assertFailsWith<IllegalArgumentException> {
            PathUtils.validatePath("/etc/passwd")
        }
        assertFailsWith<IllegalArgumentException> {
            PathUtils.validatePath("\\windows\\system32")
        }
    }

    @Test
    fun `windows drive letter blocked`() {
        assertFailsWith<IllegalArgumentException> {
            PathUtils.validatePath("C:\\Users\\file.txt")
        }
        assertFailsWith<IllegalArgumentException> {
            PathUtils.validatePath("D:/data/file.txt")
        }
    }

    @Test
    fun `normal relative path passes`() {
        PathUtils.validatePath("uploads/avatar.jpg")
        PathUtils.validatePath("documents/2026/02/report.pdf")
        PathUtils.validatePath("file.txt")
    }

    @Test
    fun `resolvePath joins correctly`() {
        assertEquals("/data/uploads/avatar.jpg", PathUtils.resolvePath("/data/uploads", "avatar.jpg"))
        assertEquals("./uploads/sub/file.txt", PathUtils.resolvePath("./uploads", "sub/file.txt"))
        assertEquals("/data/file.txt", PathUtils.resolvePath("/data/", "file.txt"))
    }

    @Test
    fun `resolvePath normalizes backslash and double slash`() {
        assertEquals("/data/uploads/sub/file.txt", PathUtils.resolvePath("/data/uploads", "sub\\file.txt"))
        assertEquals("/data/uploads/sub/file.txt", PathUtils.resolvePath("/data/uploads", "sub//file.txt"))
    }

    @Test
    fun `parentDir extracts parent`() {
        assertEquals("/data/uploads", PathUtils.parentDir("/data/uploads/file.txt"))
        assertNull(PathUtils.parentDir("file.txt"))
    }
}
