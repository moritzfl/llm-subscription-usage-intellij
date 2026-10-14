package de.moritzf.quota.compatible

import java.net.URI

/** A Chat Completions API root, including any provider-specific path such as /v1. */
data class CompatibleEndpoint(
    val baseUri: URI,
    val modelIds: List<String> = emptyList(),
    val visionModel: String? = null,
    val documentModel: String? = null,
    val liteLlm: Boolean = false,
) {
    fun uri(path: String): URI = URI.create(baseUri.toString().trimEnd('/') + "/" + path)

    /** LiteLLM management routes sit outside /v1, but retain reverse-proxy path prefixes. */
    fun managementUri(path: String): URI =
        URI.create(baseUri.toString().trimEnd('/').removeSuffix("/v1") + "/" + path)

    companion object {
        fun parse(baseUrl: String, modelIds: String = ""): CompatibleEndpoint {
            val text = baseUrl.trim().trimEnd('/')
            require(text.isNotEmpty()) { "Enter the API base URL." }
            val uri = runCatching { URI(text) }.getOrNull()
            require(
                uri != null &&
                    uri.scheme?.lowercase() in setOf("https", "http") &&
                    !uri.host.isNullOrBlank()
            ) {
                "Enter a valid http:// or https:// API base URL."
            }
            require(uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                "Use a base URL without credentials, query parameters, or a fragment."
            }
            require(uri.port == -1 || uri.port in 1..65535) {
                "The URL port must be between 1 and 65535."
            }
            require(
                !listOf("/chat/completions", "/responses", "/models").any { uri.path.endsWith(it) }
            ) {
                "Enter the API root (for example https://api.example.com/v1), without /chat/completions, /responses, or /models."
            }
            return CompatibleEndpoint(uri, parseModelIds(modelIds))
        }

        fun parseModelIds(text: String): List<String> =
            text.split('\n', ',').map(String::trim).filter(String::isNotEmpty).distinct()
    }
}
