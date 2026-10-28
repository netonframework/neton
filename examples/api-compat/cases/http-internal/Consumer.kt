package compatibility
fun internalDefaults() = neton.http.client.HttpClientConfig().toEffectiveTimeout()
