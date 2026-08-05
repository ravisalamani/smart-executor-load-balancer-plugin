package io.github.ravisalamani.jenkins.loadbalancer;

import java.util.logging.Logger;

/**
 * Classifies a {@link Throwable} caught at executor-task level into a
 * {@link FailureType} and extracts a one-line human-readable reason.
 *
 * <p>The classification is intentionally conservative: when uncertain
 * whether a failure is a node problem or a code problem, we default to
 * {@link FailureType#CODE_FAULT} so the node is not unfairly penalised.
 */
public final class FailureClassifier {

    private static final Logger LOGGER =
            Logger.getLogger(FailureClassifier.class.getName());

    private static final int MAX_REASON_LENGTH = 250;

    private FailureClassifier() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Determine the failure type from the exception that terminated an
     * executor slot.
     *
     * @param t the throwable; may be null (treated as {@link FailureType#UNKNOWN})
     */
    public static FailureType classify(Throwable t) {
        return classifyRecursive(t, 0);
    }

    private static FailureType classifyRecursive(Throwable t, int depth) {
        if (t == null || depth > 5) return FailureType.UNKNOWN;

        String cls = t.getClass().getName();
        String msg = message(t);

        // --- Aborts / interruptions -------------------------------------------
        // FlowInterruptedException extends InterruptedException, so check it FIRST —
        // otherwise the generic instanceof check below short-circuits to ABORTED.
        if (cls.contains("FlowInterruptedException")) {
            return classifyFlowInterruption(t);
        }

        if (t instanceof InterruptedException) return FailureType.ABORTED;

        // --- Node-environment faults ------------------------------------------
        // Remoting/channel issues — the agent dropped out
        if (cls.contains("ChannelClosed")
                || cls.contains("RemotingSystem")
                || cls.contains("RequestAbortedException")
                || cls.contains("ChannelException")
                || cls.contains("DiagnosedStreamCorruptionException")) {
            return FailureType.NODE_FAULT;
        }

        // Common OS-level messages that indicate node problems
        if (containsAny(msg,
                "no space left on device",
                "disk quota exceeded",
                "out of memory",
                "java.lang.outofmemoryerror",
                "agent is not connected",
                "agent went offline",
                "connection reset by peer",
                "connection refused",
                "broken pipe",
                "input/output error",
                "cannot allocate memory")) {
            return FailureType.NODE_FAULT;
        }

        // Tool / launcher resolution failures
        if (containsAny(msg,
                "command not found",
                "no such file or directory",
                "executable not found",
                "cannot find the specified path",
                "launcher failed",
                "failed to launch")) {
            // Distinguish "tool not on PATH" (node fault) from
            // "test binary not found" (code fault) by checking if the
            // exception is at the launcher level vs script level
            if (cls.contains("IOException") && !cls.contains("AbortException")) {
                return FailureType.NODE_FAULT;
            }
        }

        // --- Downstream job failure -------------------------------------------
        // BuildTriggerStep throws AbortException whose message looks like:
        //   "some-job #N completed with status FAILURE"
        //   "some-job #N failed"
        if (t instanceof hudson.AbortException) {
            if (msg.matches("(?i).*#\\d+\\s+(completed with status (failure|unstable)|failed).*")) {
                return FailureType.DOWNSTREAM_FAIL;
            }
            // Normal shell/script failures
            if (containsAny(msg, "script returned exit code", "exit code")) {
                return FailureType.CODE_FAULT;
            }
            // Default AbortException treatment: code fault
            return FailureType.CODE_FAULT;
        }

        // Cause-chain search for anything we might have missed
        Throwable cause = t.getCause();
        if (cause != null && cause != t) {
            FailureType fromCause = classifyRecursive(cause, depth + 1);
            if (fromCause != FailureType.UNKNOWN && fromCause != FailureType.CODE_FAULT) {
                return fromCause;
            }
        }

        return FailureType.UNKNOWN;
    }

    /**
     * Extract a concise one-line summary from the exception for storage.
     * Never returns a stack trace — just the message chain.
     */
    public static String extractReason(Throwable t) {
        if (t == null) return null;

        StringBuilder sb = new StringBuilder();
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth < 3) {
            String m = cur.getMessage();
            if (m != null && !m.isBlank()) {
                if (sb.length() > 0) sb.append(" → ");
                sb.append(m.strip().replace('\n', ' ').replace('\r', ' '));
            }
            cur = (cur.getCause() != cur) ? cur.getCause() : null;
            depth++;
        }

        if (sb.length() == 0) {
            sb.append(t.getClass().getSimpleName());
        }

        return sb.length() > MAX_REASON_LENGTH
                ? sb.substring(0, MAX_REASON_LENGTH) + "…"
                : sb.toString();
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static FailureType classifyFlowInterruption(Throwable t) {
        // Check message first — agent reconnect timeout produces a distinctive message
        // before getCauses() is even inspected.
        String msg = message(t);
        if (containsAny(msg, "timeout waiting for agent", "agent took too long",
                "assuming it is not coming back")) {
            return FailureType.NODE_FAULT;
        }

        // FlowInterruptedException carries CauseOfInterruption objects.
        // We inspect them via reflection to avoid hard-coupling to workflow-api.
        try {
            java.lang.reflect.Method getCauses = t.getClass().getMethod("getCauses");
            Iterable<?> causes = (Iterable<?>) getCauses.invoke(t);
            for (Object cause : causes) {
                String causeCls = cause.getClass().getName();
                if (causeCls.contains("ExceededTimeout") || causeCls.contains("Timeout")) {
                    return FailureType.TIMEOUT;
                }
                if (causeCls.contains("UserInterrupt") || causeCls.contains("AbortWork")) {
                    return FailureType.ABORTED;
                }
                // Agent disappeared mid-build
                if (causeCls.contains("AgentOffline") || causeCls.contains("AgentKilled")
                        || causeCls.contains("AgentReconnect") || causeCls.contains("LostContact")) {
                    return FailureType.NODE_FAULT;
                }
            }
        } catch (ReflectiveOperationException ignored) {
            // reflection failed — fall through
        }
        // Default: treat pipeline interruption as abort
        return FailureType.ABORTED;
    }

    private static String message(Throwable t) {
        String m = t.getMessage();
        return m != null ? m.toLowerCase() : "";
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }
}
