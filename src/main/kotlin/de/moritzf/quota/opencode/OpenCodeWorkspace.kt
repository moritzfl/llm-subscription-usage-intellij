package de.moritzf.quota.opencode

/**
 * Represents an OpenCode workspace with subscription info.
 */
data class OpenCodeWorkspace(
    val id: String,
    val name: String,
    val mine: Boolean,
    val hasGoSubscription: Boolean,
) {
    override fun toString(): String {
        val parts = mutableListOf<String>()
        if (name.isNotBlank() && name != id) {
            parts.add("$name ($id)")
        } else {
            parts.add(id)
        }
        if (mine) parts.add("[mine]")
        if (hasGoSubscription) parts.add("[Go]")
        return parts.joinToString(" ")
    }
}
