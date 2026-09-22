package de.moritzf.quota.opencode

import kotlinx.serialization.Serializable

/** A Console organization (including migrated workspace IDs). */
@Serializable
data class OpenCodeWorkspace(
    val id: String,
    val name: String = "",
) {
    override fun toString(): String = if (name.isBlank() || name == id) id else "$name ($id)"
}
