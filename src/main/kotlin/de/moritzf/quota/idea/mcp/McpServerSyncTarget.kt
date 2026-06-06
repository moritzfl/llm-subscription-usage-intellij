package de.moritzf.quota.idea.mcp

data class McpServerSyncTarget(
    var jsonFilePath: String = "",
    var jsonPropertyPath: String = "",
    var transportType: String = McpServerTransport.SSE.name,
) {
    fun isConfigured(): Boolean = jsonFilePath.isNotBlank() && jsonPropertyPath.isNotBlank()

    fun transport(): McpServerTransport = McpServerTransport.fromStorageValue(transportType)

    fun normalized(): McpServerSyncTarget {
        return copy(
            jsonFilePath = jsonFilePath.trim(),
            jsonPropertyPath = jsonPropertyPath.trim(),
            transportType = transport().name,
        )
    }
}
