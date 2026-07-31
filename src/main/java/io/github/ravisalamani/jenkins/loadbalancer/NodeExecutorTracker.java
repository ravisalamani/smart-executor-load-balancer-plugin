package io.github.ravisalamani.jenkins.loadbalancer;

import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.ExecutorListener;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;

import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Listens to every executor-slot lifecycle event (for both freestyle and
 * pipeline agent blocks) and records the outcome in {@link NodeLabelStatsStore}.
 *
 * <p>This fires at the executor level — one record per agent allocation, not
 * per job.  For a pipeline with {@code agent { label 'x' }} per stage, each
 * stage allocation fires independently.
 *
 * <p><b>What is captured here:</b>
 * <ul>
 *   <li>{@code taskCompletedWithProblems} — an exception reached the executor
 *       (channel crash, OOM, AbortException from a failed step, etc.).</li>
 *   <li>{@code taskCompleted} — the slot finished without exception; we record
 *       a SUCCESS so the ring-buffer has positive signal too.</li>
 * </ul>
 *
 * <p>For freestyle builds where the shell returns a non-zero exit code without
 * throwing (i.e., {@code taskCompleted} fires but the build result is FAILURE),
 * the additional {@link FreestyleBuildTracker} RunListener catches those.
 */
@Extension
public class NodeExecutorTracker implements ExecutorListener {

    private static final Logger LOGGER =
            Logger.getLogger(NodeExecutorTracker.class.getName());

    /** executor hashCode → load-at-start; cleared on task completion. */
    private final ConcurrentHashMap<Integer, Double> loadAtStartMap =
            new ConcurrentHashMap<>();

    /**
     * executor hashCode → label expression captured at start time.
     *
     * Pipeline PlaceholderTask.getAssignedLabel() returns the originally
     * requested label (e.g. "fib_linux") when the task is accepted, but then
     * changes to the specific node name (e.g. "fib-node-4") once execution
     * begins. Capturing here ensures the same label key used by
     * SmartLoadBalancer.map() is also used for stats recording.
     */
    private final ConcurrentHashMap<Integer, String> labelAtStartMap =
            new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // ExecutorListener callbacks
    // -------------------------------------------------------------------------

    @Override
    public void taskStarted(Executor executor, Queue.Task task) {
        try {
            int key = System.identityHashCode(executor);
            Computer computer = executor.getOwner();
            if (computer != null) {
                double load = SystemLoadMonitor.getLoad(computer.getName());
                loadAtStartMap.put(key, load);
            }
            labelAtStartMap.put(key, labelExpr(task));
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "SmartLB: error capturing label/load at start", e);
        }
    }

    @Override
    public void taskCompletedWithProblems(Executor executor,
                                          Queue.Task task,
                                          long durationMS,
                                          Throwable problems) {
        try {
            record(executor, task, durationMS, problems);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "SmartLB: error recording executor fault", e);
        }
    }

    @Override
    public void taskCompleted(Executor executor,
                               Queue.Task task,
                               long durationMS) {
        // AbstractProject (freestyle/matrix) outcomes are recorded by
        // FreestyleBuildTracker, which knows the actual result. Recording NONE
        // here would add a second entry per build, halving the effective window.
        if (task instanceof AbstractProject) return;
        try {
            record(executor, task, durationMS, null);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "SmartLB: error recording executor completion", e);
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void record(Executor executor, Queue.Task task,
                        long durationMS, Throwable problems) {
        Computer computer = executor.getOwner();
        Node node = computer.getNode();
        if (node == null) return;

        String nodeName  = node.getNodeName();
        int key = System.identityHashCode(executor);
        String captured = labelAtStartMap.remove(key);
        final String labelExpr = captured != null ? captured : labelExpr(task);

        FailureType type;
        String reason;

        if (problems == null) {
            type   = FailureType.NONE;
            reason = null;
        } else {
            type   = FailureClassifier.classify(problems);
            reason = FailureClassifier.extractReason(problems);

            LOGGER.fine(() -> String.format(
                    "SmartLB: executor fault on (%s, %s): %s — %s",
                    nodeName, labelExpr, type, reason));
        }

        String stageName = stageNameFrom(task);
        Double loadBoxed = loadAtStartMap.remove(key);
        double load = loadBoxed != null ? loadBoxed : 0.0;

        NodeLabelStatsStore store = NodeLabelStatsStore.get();
        if (store != null) {
            store.addRecord(nodeName, labelExpr,
                    new BuildRecord(System.currentTimeMillis(),
                            type, reason, durationMS, stageName, load));
        }
    }

    /** Extract the label expression the executor was allocated for. */
    private static String labelExpr(Queue.Task task) {
        if (task == null) return "";
        Label label = task.getAssignedLabel();
        return label != null ? label.getExpression() : "";
    }

    /**
     * Try to get a human-readable stage/task name for pipeline stages.
     * {@code task.getDisplayName()} works for both freestyle and pipeline
     * PlaceholderTask without importing workflow classes.
     */
    private static String stageNameFrom(Queue.Task task) {
        if (task == null) return null;
        try {
            String dn = task.getDisplayName();
            // PlaceholderTask display name tends to be verbose; truncate
            if (dn != null && dn.length() > 120) dn = dn.substring(0, 120) + "…";
            return dn;
        } catch (Exception e) {
            return null;
        }
    }
}
