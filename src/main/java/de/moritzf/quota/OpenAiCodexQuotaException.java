package de.moritzf.quota;

import java.io.IOException;

/**
 * Signals a quota API request or response error with the associated HTTP status code.
 */
public class OpenAiCodexQuotaException extends IOException {
    private final int statusCode;

    public OpenAiCodexQuotaException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
