package de.moritzf.quota;

import java.time.Instant;

/**
 * Represents usage information for one quota window.
 */
public class UsageWindow {
    private double usedPercent;
    private Integer windowMinutes;
    private Instant resetsAt;

    public double getUsedPercent() {
        return usedPercent;
    }

    public void setUsedPercent(double usedPercent) {
        this.usedPercent = usedPercent;
    }

    public Integer getWindowMinutes() {
        return windowMinutes;
    }

    public void setWindowMinutes(Integer windowMinutes) {
        this.windowMinutes = windowMinutes;
    }

    public Instant getResetsAt() {
        return resetsAt;
    }

    public void setResetsAt(Instant resetsAt) {
        this.resetsAt = resetsAt;
    }

    @Override
    public String toString() {
        return "UsageWindow{" +
                "usedPercent=" + usedPercent +
                ", windowMinutes=" + windowMinutes +
                ", resetsAt=" + resetsAt +
                '}';
    }
}
