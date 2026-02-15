package neton.storage

open class StorageException(message: String, cause: Throwable? = null) : Exception(message, cause)

class StorageNotFoundException(path: String) : StorageException("Not found: $path")

class StorageAccessDeniedException(path: String) : StorageException("Access denied: $path")

class StorageAlreadyExistsException(path: String) : StorageException("Already exists: $path")
