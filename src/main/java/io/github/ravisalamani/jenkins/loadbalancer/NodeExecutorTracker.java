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
 * Listens to every executor-slot lifecycle event and records the outcome in
 * {@link NodeLabelStatsStore}.
 *
 * <h3>Task routing</h3>
 *
 * <p>For freestyle builds ({@code AbstractProject}), {@code taskCompleted} fires after the build
 * result has been set. {@link FreestyleBuildTracker} handles those via {@code RunListener}.
 * {@code NodeExecutorTracker} skips them to avoid double-counting and only cleans up maps.
 *
 * <p>For pipeline {@code node()} blocks, {@code taskCompleted} fires when the executor slot
 * exits <em>cleanly</em> — regardless of whether the pipeline script succeeded or failed.
 * A clean slot exit means the node itself was healthy; we record {@link FailureType#NONE}.
 * Infrastructure failures (channel crash, OOM, agent timeout) always surface via
 * {@code taskCompletedWithProblems} and are classified as {@link FailureType#NODE_FAULT}.
 *
 * <p>This design also correctly handles parallel pipelines: when one parallel stage fails
 * and another succeeds, each agent's slot exits independently. The failing stage's agent
 * exits cleanly (the pipeline engine swallows the script exception), so we record NONE for
 * both agents — neither node caused the failure.
 */
@Extension
public class NodeExecutorTracker implements ExecutorListener {

    private static final Logger LOGGER =
            Logger.getLogger(NodeExecutorTracker.class.getName());

    /** executor identity hash → system load captured at task start. */
    final ConcurrentHashMap<Integer, Double> loadAtStartMap = new ConcurrentHashMap<>();

    /**
     * executor identity hash → label expression captured at task start.
     *
     * <p>Captured at start because {@code PlaceholderTask.getAssignedLabel()} may change from
     * the requested label (e.g. "fib_linux") to the actual node name once the task is dispatched.
     */
    final ConcurrentHashMap<Integer, String> labelAtStartMap = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // ExecutorListener callbacks
    // -------------------------------------------------------------------------

    @Override
    public void taskStarted(Executor executor, Queue.Task task) {
        try {
            int key = System.identityHashCode(executor);
            Computer computer = executor.getOwner();
            double load = computer != null ? SystemLoadMonitor.getLoad(computer.getName()) : 0.0;
            loadAtStartMap.put(key, load);
            labelAtStartMap.put(key, labelExpr(task));
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "SmartLB: error capturing start state", e);
        }
    }

    @Override
    public void taskCompletedWithProblems(Executor executor, Queue.Task task,
                                          long durationMS, Throwable problems) {
        try {
            record(executor, task, durationMS, problems, true);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "SmartLB: error recording executor fault", e);
        }
    }

    @Override
    public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
        int key = System.identityHashCode(executor);
        if (task instanceof AbstractProject) {
            // FreestyleBuildTracker handles recording for freestyle; just clean up maps.
            loadAtStartMap.remove(key);
            labelAtStartMap.remove(key);
            return;
        }
        // Clean slot exit — node was healthy. Record NONE.
        try {
            record(executor, task, durationMS, null, false);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "SmartLB: error recording executor completion", e);
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void record(Executor executor, Queue.Task task,
                        long durationMS, Throwable problems, boolean hadProblems) {
        int key = System.identityHashCode(executor);
        String captured  = labelAtStartMap.remove(key);
        Double loadBoxed = loadAtStartMap.remove(key);

        Computer computer = executor.getOwner();
        Node node = computer != null ? computer.getNode() : null;
        if (node == null) return;

        String nodeName = node.getNodeName();
        if (nodeName == null || nodeName.isBlank()) return;

        final String labelExpr = captured != null ? captured : labelExpr(task);

        FailureType type;
        String reason;

        if (problems != null) {
            type   = FailureClassifier.classify(problems);
            reason = FailureClassifier.extractReason(problems);
            LOGGER.fine(() -> String.format(
                    "SmartLB: executor fault on (%s, %s): %s — %s",
                    nodeName, labelExpr, type, reason));
        } else if (hadProblems) {
            type   = FailureType.CODE_FAULT;
            reason = "build failed (no exception)";
        } else {
            type   = FailureType.NONE;
            reason = null;
        }

        String stageName = stageNameFrom(task);
        double load = loadBoxed != null ? loadBoxed : 0.0;

        NodeLabelStatsStore store = NodeLabelStatsStore.get();
        if (store != null) {
            store.addRecord(nodeName, "",
                    new BuildRecord(System.currentTimeMillis(),
                            type, reason, durationMS, stageName, load));
        }
    }

    private static String labelExpr(Queue.Task task) {
        if (task == null) return "";
        Label label = task.getAssignedLabel();
        return label != null ? label.getExpression() : "";
    }

    private static String stageNameFrom(Queue.Task task) {
        if (task == null) return null;
        try {
            String dn = task.getDisplayName();
            if (dn != null && dn.length() > 120) dn = dn.substring(0, 120) + "…";
            return dn;
        } catch (Exception e) {
            return null;
        }
    }
}
