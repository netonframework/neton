package neton.storage

import neton.http.client.HttpClient
import neton.logging.Logger
import neton.storage.local.LocalStorageOperator
import neton.storage.s3.S3StorageOperator

/** Creates an operator without owning the borrowed HTTP client. Configuration must be trusted. */
object StorageOperatorFactory {
    fun create(config: SourceConfig, httpClient: HttpClient? = null, logger: Logger? = null): StorageOperator =
        when (config.type) {
            "local" -> LocalStorageOperator(config.name, config.basePath, logger, config.baseUrl)
            "s3" -> S3StorageOperator(
                name = config.name, endpoint = config.endpoint, region = config.region,
                bucket = config.bucket, accessKey = config.accessKey, secretKey = config.secretKey,
                pathStyle = config.pathStyle, httpClient = requireNotNull(httpClient) { "S3 requires a borrowed HttpClient" },
                logger = logger, baseUrl = config.baseUrl,
            )
            else -> error("Unsupported storage type")
        }
}
