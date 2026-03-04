package de.moritzf.quota.idea;

import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * UI formatting helpers for timestamps and relative quota time values.
 */
public final class QuotaUiUtil {
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm", Locale.ENGLISH);

    private QuotaUiUtil() {
    }

    public static @Nullable String formatReset(@Nullable Instant resetsAt) {
        if (resetsAt == null) {
            return null;
        }
        Duration duration = Duration.between(Instant.now(), resetsAt);
        String remaining = formatDuration(duration);
        if (remaining != null) {
            return "Resets " + remaining;
        }
        ZonedDateTime zdt = resetsAt.atZone(ZoneId.systemDefault());
        String at = zdt.format(DATE_TIME);
        return "Resets at " + at;
    }

    public static @Nullable String formatInstant(@Nullable Instant instant) {
        if (instant == null) {
            return null;
        }
        Duration duration = Duration.between(instant, Instant.now());
        String ago = formatAgo(duration);
        return ago != null ? ago : instant.atZone(ZoneId.systemDefault()).format(DATE_TIME);
    }

    private static @Nullable String formatDuration(Duration duration) {
        if (duration.isNegative()) {
            return null;
        }
        long minutes = duration.toMinutes();
        if (minutes < 1) {
            return "in <1m";
        }
        long days = minutes / (60 * 24);
        long hours = (minutes % (60 * 24)) / 60;
        long mins = minutes % 60;
        StringBuilder builder = new StringBuilder("in ");
        if (days > 0) {
            builder.append(days).append("d");
        }
        if (hours > 0) {
            if (builder.length() > 3) {
                builder.append(" ");
            }
            builder.append(hours).append("h");
        }
        if (mins > 0 && days == 0) {
            if (builder.length() > 3) {
                builder.append(" ");
            }
            builder.append(mins).append("m");
        }
        return builder.toString();
    }

    private static @Nullable String formatAgo(Duration duration) {
        if (duration.isNegative()) {
            return null;
        }
        long minutes = duration.toMinutes();
        if (minutes < 1) {
            return "just now";
        }
        long days = minutes / (60 * 24);
        if (days > 0) {
            return days == 1 ? "1 day ago" : days + " days ago";
        }
        long hours = minutes / 60;
        if (hours > 0) {
            return hours == 1 ? "1 hour ago" : hours + " hours ago";
        }
        return minutes == 1 ? "1 minute ago" : minutes + " minutes ago";
    }
}
