package io.github.ravisalamani.jenkins.loadbalancer;

import java.io.Serializable;

/**
 * Immutable snapshot of one build execution on a specific executor slot.
 * Stored in the ring-buffer inside {@link NodeLabelStats}.
 */
public final class BuildRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    private final long      timestamp;   // System.currentTimeMillis() at completion
    private final FailureType failureType;
    private final String    reason;      // one-line summary; null on success
    private final long      durationMs;
    private final String    stageName;   // pipeline stage name; null for freestyle
    private final double    loadAtStart; // 1-min system load average when build started (0 = unknown)

    public BuildRecord(long timestamp, FailureType failureType,
                       String reason, long durationMs, String stageName,
                       double loadAtStart) {
        this.timestamp   = timestamp;
        this.failureType = failureType == null ? FailureType.UNKNOWN : failureType;
        this.reason      = reason;
        this.durationMs  = durationMs;
        this.stageName   = stageName;
        this.loadAtStart = loadAtStart;
    }

    public long      getTimestamp()   { return timestamp; }
    public FailureType getFailureType() { return failureType; }
    public String    getReason()      { return reason; }
    public long      getDurationMs()  { return durationMs; }
    public String    getStageName()   { return stageName; }
    public double    getLoadAtStart() { return loadAtStart; }

    /** Duration formatted as "Xm Ys" or "Xs" for display. */
    public String getFormattedDuration() {
        long secs = durationMs / 1000;
        if (secs >= 60) return (secs / 60) + "m " + (secs % 60) + "s";
        return secs + "s";
    }

    public boolean isSuccess() {
        return failureType == FailureType.NONE;
    }

    /** Human-friendly label for the tooltip (replaces raw enum name). */
    public String getDisplayType() {
        switch (failureType) {
            case NONE:            return "SUCCESS";
            case NODE_FAULT:      return "NODE FAULT";
            case CODE_FAULT:      return "CODE FAULT";
            case DOWNSTREAM_FAIL: return "DOWNSTREAM FAIL";
            case TIMEOUT:         return "TIMEOUT";
            case ABORTED:         return "ABORTED";
            default:              return "UNKNOWN";
        }
    }

    /** True if this record counts against the node-avoidance threshold. */
    public boolean isNodeFault() {
        return failureType == FailureType.NODE_FAULT || failureType == FailureType.TIMEOUT;
    }

    /** Single character for the sparkline in the management UI. */
    public String getIcon() {
        switch (failureType) {
            case NONE:            return "✓";
            case NODE_FAULT:      return "✗";
            case CODE_FAULT:      return "○";
            case DOWNSTREAM_FAIL: return "↓";
            case TIMEOUT:         return "⏱";
            case ABORTED:         return "⊘";
            default:              return "?";
        }
    }

    /** CSS colour class for the icon in the management UI. */
    public String getIconColor() {
        switch (failureType) {
            case NONE:            return "green";
            case NODE_FAULT:      return "red";
            case TIMEOUT:         return "red";
            case CODE_FAULT:      return "orange";
            case DOWNSTREAM_FAIL: return "gray";
            case ABORTED:         return "gray";
            default:              return "gray";
        }
    }
}
