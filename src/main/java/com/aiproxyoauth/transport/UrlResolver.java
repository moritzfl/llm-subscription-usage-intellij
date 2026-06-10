package com.aiproxyoauth.transport;

import java.net.URI;

public final class UrlResolver {

    private static final String API_V1_PREFIX = "/v1";

    private UrlResolver() {}

    public static String resolveTargetUrl(String input, String baseUrl) {
        URI base;
        try {
            base = URI.create(baseUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid base URL: " + baseUrl, e);
        }
        String basePath = stripTrailingSlash(base.getPath());
        String origin = base.getScheme() + "://" + base.getAuthority();

        String pathname;
        String query = "";

        if (input.startsWith("http://") || input.startsWith("https://")) {
            URI parsed;
            try {
                parsed = URI.create(input);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid input URL: " + input, e);
            }
            pathname = parsed.getPath();
            query = parsed.getRawQuery() != null ? "?" + parsed.getRawQuery() : "";
        } else {
            // Relative path
            int qIdx = input.indexOf('?');
            if (qIdx >= 0) {
                pathname = input.substring(0, qIdx);
                query = input.substring(qIdx);
            } else {
                pathname = input;
            }
        }

        // Strip base path prefix
        if (pathname.equals(basePath)) {
            pathname = "/";
        } else if (!basePath.isEmpty() && pathname.startsWith(basePath + "/")) {
            pathname = pathname.substring(basePath.length());
        }

        // Strip /v1 prefix
        if (pathname.equals(API_V1_PREFIX)) {
            pathname = "/";
        } else if (pathname.startsWith(API_V1_PREFIX + "/")) {
            pathname = pathname.substring(API_V1_PREFIX.length());
        }

        return origin + basePath + pathname + query;
    }

    private static String stripTrailingSlash(String path) {
        if (path == null) return "";
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }
}
