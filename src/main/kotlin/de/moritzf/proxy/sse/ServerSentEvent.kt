package de.moritzf.proxy.sse
data class ServerSentEvent(
    val event: String?,
    val data: String?,
) {
    fun event(): String? = event
    fun data(): String? = data
}
