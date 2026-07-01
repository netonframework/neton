@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package neton.core

import platform.posix.SIGTERM
import platform.posix.raise
import kotlin.test.Test
import kotlin.test.assertTrue

class ProcessShutdownSignalsTest {
    @Test
    fun sigtermSetsShutdownFlag() {
        ProcessShutdownSignals.install()
        try {
            raise(SIGTERM)
            assertTrue(ProcessShutdownSignals.isRequested())
        } finally {
            ProcessShutdownSignals.reset()
        }
    }
}
