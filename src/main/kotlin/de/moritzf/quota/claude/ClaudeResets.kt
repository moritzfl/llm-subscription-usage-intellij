package de.moritzf.quota.claude

import kotlin.time.Instant

/**
 * Free usage-limit resets on offer to the account. [grants] are the resets listed under claude.ai
 * Settings > Usage (`cedar_ember`). [sessionReset] is the weekly session reset behind Claude Code's
 * `/limit-reset` (`juniper_tide`), which the server reports only at a usage limit.
 */
data class ClaudeResets(
    val grants: List<ClaudeResetGrant> = emptyList(),
    val nextGrantId: String? = null,
    val sessionReset: ClaudeSessionReset? = null,
) {
    /** Grants with resets left that are not paused, whether or not they can be used right now. */
    fun offeredGrants(): List<ClaudeResetGrant> = grants.filter { it.resetsLeft > 0 && !it.paused }

    fun availableCount(): Int =
        offeredGrants().sumOf { it.resetsLeft } + if (sessionReset != null) 1 else 0

    /** Resets that can be used now, with the grant the server suggests first. */
    fun choices(): List<ClaudeResetChoice> = buildList {
        sessionReset?.let { add(ClaudeResetChoice.Session(it)) }
        offeredGrants()
            .filter { it.usableNow }
            .sortedByDescending { it.id == nextGrantId }
            .forEach { add(ClaudeResetChoice.Grant(it)) }
    }
}

data class ClaudeResetGrant(
    val id: String,
    val label: String,
    val resetsLeft: Int,
    val endsAt: Instant?,
    val paused: Boolean,
    /** False when the grant only works once a usage limit is reached. */
    val usableNow: Boolean,
    /** Usage windows the grant refills, such as `five_hour` or `seven_day`. */
    val clears: List<String>,
)

/** The weekly session reset is on offer now. */
data class ClaudeSessionReset(val weeklyResetsAt: Instant?)

sealed interface ClaudeResetChoice {
    data class Session(val reset: ClaudeSessionReset) : ClaudeResetChoice

    data class Grant(val grant: ClaudeResetGrant) : ClaudeResetChoice
}

/**
 * Answer to a reset claim. [result] is `reset` when the limits were refilled; otherwise one of
 * `already_used`, `not_limited`, `cooldown`, `ineligible`, `unavailable`, or `rate_limited`.
 * Nothing was used unless the result is `reset`.
 */
data class ClaudeResetOutcome(val result: String) {
    val succeeded: Boolean
        get() = result == RESET

    companion object {
        const val RESET = "reset"
        const val RATE_LIMITED = "rate_limited"
    }
}
