package de.moritzf.quota.idea.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * Handles loading, saving, and clearing OAuth credentials in PasswordSafe.
 */
public final class OAuthCredentialsStore {
    private static final Logger LOG = Logger.getInstance(OAuthCredentialsStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CredentialAttributes attributes;
    private final String userName;

    public OAuthCredentialsStore(@NotNull String serviceName, @NotNull String userName) {
        this.attributes = new CredentialAttributes(serviceName, userName);
        this.userName = userName;
    }

    public @Nullable OAuthCredentials load() {
        Credentials stored = PasswordSafe.getInstance().get(attributes);
        if (stored == null || stored.getPasswordAsString() == null) {
            return null;
        }
        String json = stored.getPasswordAsString();
        if (json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, OAuthCredentials.class);
        } catch (Exception exception) {
            LOG.warn("Failed to parse stored credentials", exception);
            return null;
        }
    }

    public void save(@NotNull OAuthCredentials credentials) throws IOException {
        String json = MAPPER.writeValueAsString(credentials);
        PasswordSafe.getInstance().set(attributes, new Credentials(userName, json));
    }

    public void clear() {
        try {
            PasswordSafe.getInstance().set(attributes, null);
        } catch (Exception exception) {
            LOG.warn("Failed to clear stored OAuth credentials", exception);
        }
    }
}
