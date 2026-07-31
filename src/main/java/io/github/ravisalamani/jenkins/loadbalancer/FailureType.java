package io.github.ravisalamani.jenkins.loadbalancer;

/**
 * Classifies why a build failed on a particular executor slot.
 *
 * Only {@link #NODE_FAULT} and {@link #TIMEOUT} count toward the node-avoidance
 * threshold — they indicate an environment problem on the node itself.
 * The rest are recorded for information but do not penalise the node.
 */
public enum FailureType {

    /** Build succeeded — positive signal for the node. */
    NONE,

    /**
     * The node's environment caused the failure: agent disconnected, channel
     * closed, out-of-memory, disk full, required tool absent, launcher error.
     * Counts toward the avoidance threshold.
     */
    NODE_FAULT,

    /**
     * The build code/tests failed (non-zero exit code, compile error, test
     * failure).  Not the node's fault.
     */
    CODE_FAULT,

    /**
     * A {@code build job:'…'} trigger step failed.  The downstream job is the
     * problem; this node just ran the trigger step correctly.
     */
    DOWNSTREAM_FAIL,

    /**
     * The build was killed by a timeout step.  Counts toward the threshold
     * because it may indicate the node is overloaded or starved of resources.
     */
    TIMEOUT,

    /** Explicitly aborted by a user or the system — not a node problem. */
    ABORTED,

    /** Could not determine the cause. Recorded but not counted. */
    UNKNOWN
}
