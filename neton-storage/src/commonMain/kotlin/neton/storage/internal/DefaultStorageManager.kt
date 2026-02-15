package neton.storage.internal

import neton.storage.StorageManager
import neton.storage.StorageOperator

internal class DefaultStorageManager(
    private val operators: Map<String, StorageOperator>
) : StorageManager {

    override fun get(name: String): StorageOperator =
        operators[name] ?: throw IllegalStateException("Storage source '$name' not found. Available: ${operators.keys}")

    override fun default(): StorageOperator = get("default")

    override fun names(): Set<String> = operators.keys
}
