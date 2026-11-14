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

    /**
     * Every reset on offer, as claude.ai groups them: the session reset, then one choice per kind
     * of grant. Usable choices come first, each ordered by the expiry of the grant it would use.
     */
    fun offeredChoices(): List<ClaudeResetChoice> = buildList {
        sessionReset?.let { add(ClaudeResetChoice.Session(it)) }
        val claimOrder =
            compareByDescending<ClaudeResetGrant> { it.id == nextGrantId }
                .thenBy(nullsLast()) { it.endsAt }
        offeredGrants()
            .groupBy { Triple(it.kind, it.clears.toSet(), it.usableNow) }
            .values
            .map { ClaudeResetChoice.Grant(it.sortedWith(claimOrder)) }
            .sortedWith(
                compareByDescending<ClaudeResetChoice.Grant> { it.grant.usableNow }
                    .thenBy(nullsLast()) { it.grant.endsAt },
            )
            .forEach(::add)
    }

    /** Resets that can be used now. */
    fun choices(): List<ClaudeResetChoice> =
        offeredChoices().filter { it !is ClaudeResetChoice.Grant || it.grant.usableNow }

    companion object {
        /**
         * Grants from the regular read, completed by an at-the-limit read, which alone reports the
         * session reset. Returns null when neither offers anything.
         */
        fun merge(regular: ClaudeResets?, atLimit: ClaudeResets?): ClaudeResets? {
            val grantSource = regular?.takeIf { it.grants.isNotEmpty() } ?: atLimit
            val sessionReset = atLimit?.sessionReset ?: regular?.sessionReset
            val grants = grantSource?.grants.orEmpty()
            if (grants.isEmpty() && sessionReset == null) return null
            return ClaudeResets(grants, grantSource?.nextGrantId, sessionReset)
        }
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
    /** The grant also returns what Fable used of the weekly limit, without resetting it. */
    val givesBackWeekly: Boolean = false,
    /** The server's `session`, `weekly`, or `full`; null when it names none. */
    val type: String? = null,
) {
    /** The kind Claude Code and claude.ai name the grant by. */
    val kind: ClaudeResetKind
        get() =
            when {
                givesBackWeekly -> ClaudeResetKind.FABLE
                type == "session" -> ClaudeResetKind.SESSION
                type == "weekly" -> ClaudeResetKind.WEEKLY
                type == "full" -> ClaudeResetKind.FULL
                clears.isNotEmpty() && clears.all { it == "five_hour" } -> ClaudeResetKind.SESSION
                clears.isNotEmpty() && "five_hour" !in clears -> ClaudeResetKind.WEEKLY
                else -> ClaudeResetKind.FULL
            }
}

enum class ClaudeResetKind {
    SESSION,
    WEEKLY,
    FULL,
    FABLE,
}

/** The weekly session reset is on offer now. */
data class ClaudeSessionReset(val weeklyResetsAt: Instant?)

sealed interface ClaudeResetChoice {
    data class Session(val reset: ClaudeSessionReset) : ClaudeResetChoice

    /**
     * Grants of one kind; a claim uses the first, which the server or the earliest expiry picks.
     */
    data class Grant(val grants: List<ClaudeResetGrant>) : ClaudeResetChoice {
        constructor(grant: ClaudeResetGrant) : this(listOf(grant))

        init {
            require(grants.isNotEmpty())
        }

        val grant: ClaudeResetGrant
            get() = grants.first()

        val resetsLeft: Int
            get() = grants.sumOf { it.resetsLeft }
    }
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
