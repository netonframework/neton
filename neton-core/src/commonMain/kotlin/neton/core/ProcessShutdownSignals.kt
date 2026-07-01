package neton.core

internal expect object ProcessShutdownSignals {
    fun install()
    fun isRequested(): Boolean
    fun reset()
}
