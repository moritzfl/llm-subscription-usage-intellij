package com.aiproxyoauth.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Resolves the proxy's version from the {@code aiproxyoauth-version.properties} resource
 * that the build generates from the Gradle {@code pluginVersion} property, so every
 * surface (health endpoint, CLI --version) reports the plugin version. Running without
 * the generated resource (e.g. straight from an IDE compile) yields "dev".
 */
public final class ProxyVersion {

    private static final String VERSION_RESOURCE = "/aiproxyoauth-version.properties";
    private static final String DEV_VERSION = "dev";
    private static final String VERSION = resolve();

    private ProxyVersion() {}

    public static String get() {
        return VERSION;
    }

    private static String resolve() {
        try (InputStream in = ProxyVersion.class.getResourceAsStream(VERSION_RESOURCE)) {
            if (in != null) {
                Properties properties = new Properties();
                properties.load(in);
                String version = properties.getProperty("version");
                if (version != null && !version.isBlank()) {
                    return version.strip();
                }
            }
        } catch (IOException ignored) {
        }
        return DEV_VERSION;
    }
}
