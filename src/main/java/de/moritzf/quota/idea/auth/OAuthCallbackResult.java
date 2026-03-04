package de.moritzf.quota.idea.auth;

import org.jetbrains.annotations.Nullable;

/**
 * Result produced by the local OAuth callback endpoint.
 */
public record OAuthCallbackResult(@Nullable String code, @Nullable String error) {
}
