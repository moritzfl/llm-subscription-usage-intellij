package com.aiproxyoauth.auth;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class AuthFileResolver {

    private static final String AUTH_FILENAME = "auth.json";

    private AuthFileResolver() {}

    public static List<String> resolveCandidates(String authFilePath) {
        if (authFilePath != null && !authFilePath.isEmpty()) {
            return List.of(authFilePath);
        }

        LinkedHashSet<String> candidates = new LinkedHashSet<>();

        String envHome = System.getenv("CHATGPT_LOCAL_HOME");
        if (envHome != null && !envHome.isEmpty()) {
            candidates.add(Path.of(envHome, AUTH_FILENAME).toString());
        }

        String codexHome = System.getenv("CODEX_HOME");
        if (codexHome != null && !codexHome.isEmpty()) {
            candidates.add(Path.of(codexHome, AUTH_FILENAME).toString());
        }

        String userHome = System.getProperty("user.home");
        candidates.add(Path.of(userHome, ".chatgpt-local", AUTH_FILENAME).toString());
        candidates.add(Path.of(userHome, ".codex", AUTH_FILENAME).toString());

        return new ArrayList<>(candidates);
    }

    public static String resolveWritePath(String preferred) {
        if (preferred != null && !preferred.isEmpty()) {
            return preferred;
        }
        String envHome = System.getenv("CHATGPT_LOCAL_HOME");
        if (envHome == null || envHome.isEmpty()) {
            envHome = System.getenv("CODEX_HOME");
        }
        if (envHome != null && !envHome.isEmpty()) {
            return Path.of(envHome, AUTH_FILENAME).toString();
        }
        return Path.of(System.getProperty("user.home"), ".chatgpt-local", AUTH_FILENAME).toString();
    }
}
