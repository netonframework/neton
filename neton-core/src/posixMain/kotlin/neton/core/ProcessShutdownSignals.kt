@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package neton.core

import neton.env.neton_install_shutdown_signals
import neton.env.neton_reset_shutdown_signals
import neton.env.neton_shutdown_signal_received

internal actual object ProcessShutdownSignals {
    actual fun install() {
        check(neton_install_shutdown_signals() != 0) { "Unable to install process shutdown handlers" }
    }
    actual fun isRequested(): Boolean = neton_shutdown_signal_received() != 0
    actual fun reset() = neton_reset_shutdown_signals()
}
