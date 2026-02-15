package neton.storage

import neton.core.Neton

fun Neton.LaunchBuilder.storage(block: StorageConfig.() -> Unit) {
    install(StorageComponent, block)
}
