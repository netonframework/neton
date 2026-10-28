@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package neton.core

import platform.posix.SIGTERM
import platform.posix.raise
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ProcessShutdownSignalsTest {
    @Test
    fun sigtermSetsShutdownFlag() {
        ProcessShutdownSignals.install()
        try {
            raise(SIGTERM)
            assertTrue(ProcessShutdownSignals.isRequested())
            ProcessShutdownSignals.install()
            assertTrue(ProcessShutdownSignals.isRequested(), "Repeated install must not erase a pending shutdown")
        } finally {
            ProcessShutdownSignals.reset()
        }
        assertFalse(ProcessShutdownSignals.isRequested())
        ProcessShutdownSignals.install()
        try {
            assertFalse(ProcessShutdownSignals.isRequested())
            raise(SIGTERM)
            assertTrue(ProcessShutdownSignals.isRequested())
        } finally {
            ProcessShutdownSignals.reset()
        }
    }
}
