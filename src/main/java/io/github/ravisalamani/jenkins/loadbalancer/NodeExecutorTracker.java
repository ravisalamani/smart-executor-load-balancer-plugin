package io.github.ravisalamani.jenkins.loadbalancer;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.ExecutorListener;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Listens to every executor-slot lifecycle event and records the outcome in
 * {@link NodeLabelStatsStore}.
 *
 * <h3>Pipeline vs freestyle</h3>
 *
 * <p>For freestyle builds ({@code AbstractProject}), {@code taskCompleted} fires after the build
 * result has been set. {@link FreestyleBuildTracker} handles those via {@code RunListener}.
 * {@code NodeExecutorTracker} skips them to avoid double-counting.
 *
 * <p>For pipeline {@code node()} blocks, the executor slot exits via {@code taskCompleted}
 * (not {@code taskCompletedWithProblems}) even when the build fails, because the pipeline engine
 * swallows the {@code AbortException} before the slot is released. Furthermore, the
 * {@code WorkflowRun} result is not set until <em>after</em> the executor exits — so we cannot
 * read {@code run.getResult()} inside {@code taskCompleted}.
 *
 * <p>The fix: at {@code taskStarted} we extract the {@code runId} field from the
 * {@code PlaceholderTask} via reflection and stash a {@link DeferredRecord}. When
 * {@link PipelineRunListener#onCompleted} fires (after the run result is finalized), we look up
 * all pending records for that run and write them to the store.
 *
 * <p>{@code taskCompletedWithProblems} (channel crash, OOM, etc.) records immediately and cancels
 * the deferred record so the {@code RunListener} does not double-count.
 */
@Extension
public class NodeExecutorTracker implements ExecutorListener {

    private static final Logger LOGGER =
            Logger.getLogger(NodeExecutorTracker.class.getName());

    /** executor identity hash → system load captured at task start. */
    private final ConcurrentHashMap<Integer, Double> loadAtStartMap = new ConcurrentHashMap<>();

    /**
     * executor identity hash → label expression captured at task start.
     *
     * <p>Captured at start because {@code PlaceholderTask.getAssignedLabel()} may change from
     * the requested label (e.g. "fib_linux") to the actual node name once the task is dispatched.
     */
    private final ConcurrentHashMap<Integer, String> labelAtStartMap = new ConcurrentHashMap<>();

    /**
     * executor identity hash → runId string (e.g. "my-pipeline#42").
     * Only populated for non-{@code AbstractProject} tasks (i.e., pipeline {@code node()} blocks).
     */
    private final ConcurrentHashMap<Integer, String> runIdAtStartMap = new ConcurrentHashMap<>();

    /**
     * runId → list of deferred records waiting for the WorkflowRun to set its final result.
     * Static so {@link PipelineRunListener} (a separate extension) can access it without a
     * reference to the {@code NodeExecutorTracker} singleton.
     */
    static final ConcurrentHashMap<String, List<DeferredRecord>> DEFERRED_BY_RUN_ID =
            new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // DeferredRecord
    // -------------------------------------------------------------------------

    /**
     * Holds per-executor-slot data that needs the run's final result before it can be stored.
     * One instance is created per pipeline {@code node()} block. {@code durationMs} is filled in
     * at {@code taskCompleted} time; everything else is captured at {@code taskStarted}.
     */
    static final class DeferredRecord {
        /** Identity hash of the executor, used to match taskCompleted to the right record. */
        final int executorKey;
        final String nodeName;
        final String labelExpr;
        final double load;
        final String stageName;
        volatile long durationMs = 0L;

        DeferredRecord(int executorKey, String nodeName, String labelExpr,
                       double load, String stageName) {
            this.executorKey = executorKey;
            this.nodeName    = nodeName;
            this.labelExpr   = labelExpr;
            this.load        = load;
            this.stageName   = stageName;
        }
    }

    // -------------------------------------------------------------------------
    // ExecutorListener callbacks
    // -------------------------------------------------------------------------

    @Override
    public void taskStarted(Executor executor, Queue.Task task) {
        try {
            int key = System.identityHashCode(executor);

            Computer computer = executor.getOwner();
            double load = 0.0;
            if (computer != null) {
                load = SystemLoadMonitor.getLoad(computer.getName());
                loadAtStartMap.put(key, load);
            }
            labelAtStartMap.put(key, labelExpr(task));

            // For pipeline node() blocks: stash a deferred record keyed by runId.
            // We cannot read run.getResult() at taskCompleted time (not yet set),
            // so PipelineRunListener finalises the record when the WorkflowRun completes.
            if (!(task instanceof AbstractProject)) {
                String runId = runIdFromTask(task);
                if (runId != null) {
                    Node node = computer != null ? computer.getNode() : null;
                    String nodeName = node != null ? node.getNodeName() : null;
                    if (nodeName != null && !nodeName.isBlank()) {
                        runIdAtStartMap.put(key, runId);
                        DeferredRecord dr = new DeferredRecord(
                                key, nodeName, "", load, stageNameFrom(task));
                        DEFERRED_BY_RUN_ID
                                .computeIfAbsent(runId, r -> new CopyOnWriteArrayList<>())
                                .add(dr);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "SmartLB: error capturing start state", e);
        }
    }

    @Override
    public void taskCompletedWithProblems(Executor executor, Queue.Task task,
                                          long durationMS, Throwable problems) {
        int key = System.identityHashCode(executor);
        // Cancel any deferred pipeline record — record immediately with the exception instead.
        String runId = runIdAtStartMap.remove(key);
        if (runId != null) {
            removeDeferredForExecutor(runId, key);
        }
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
            runIdAtStartMap.remove(key);
            return;
        }

        String runId = runIdAtStartMap.remove(key);
        if (runId != null) {
            // Pipeline slot exited normally. The run result is not set yet — update durationMs
            // in the deferred record and let PipelineRunListener finalise it.
            List<DeferredRecord> pending = DEFERRED_BY_RUN_ID.get(runId);
            if (pending != null) {
                for (DeferredRecord dr : pending) {
                    if (dr.executorKey == key) {
                        dr.durationMs = durationMS;
                        break;
                    }
                }
            }
            loadAtStartMap.remove(key);
            labelAtStartMap.remove(key);
            return;
        }

        // No deferred record (non-pipeline task or reflection failed) — record immediately.
        try {
            record(executor, task, durationMS, null, false);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "SmartLB: error recording executor completion", e);
        }
    }

    // -------------------------------------------------------------------------
    // RunListener for pipeline builds
    // -------------------------------------------------------------------------

    /**
     * Fires when any {@link Run} completes. For pipeline runs with deferred records, writes the
     * final {@link BuildRecord}s to {@link NodeLabelStatsStore} now that the result is set.
     */
    @Extension
    public static class PipelineRunListener extends RunListener<Run<?, ?>> {

        @Override
        public void onCompleted(Run<?, ?> run, @NonNull TaskListener listener) {
            String runId = run.getExternalizableId();
            List<DeferredRecord> pending = DEFERRED_BY_RUN_ID.remove(runId);
            if (pending == null || pending.isEmpty()) return;

            Result result = run.getResult();
            FailureType type;
            String reason;
            if (result != null && result.isWorseThan(Result.SUCCESS)) {
                type   = FailureType.CODE_FAULT;
                reason = "pipeline failed (result=" + result + ")";
            } else {
                type   = FailureType.NONE;
                reason = null;
            }

            NodeLabelStatsStore store = NodeLabelStatsStore.get();
            if (store == null) return;

            long nowMs = System.currentTimeMillis();
            for (DeferredRecord dr : pending) {
                store.addRecord(dr.nodeName, dr.labelExpr,
                        new BuildRecord(nowMs, type, reason, dr.durationMs, dr.stageName, dr.load));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /** Remove the deferred record for a specific executor from the run's pending list. */
    private static void removeDeferredForExecutor(String runId, int executorKey) {
        List<DeferredRecord> pending = DEFERRED_BY_RUN_ID.get(runId);
        if (pending == null) return;
        pending.removeIf(dr -> dr.executorKey == executorKey);
        if (pending.isEmpty()) DEFERRED_BY_RUN_ID.remove(runId);
    }

    private void record(Executor executor, Queue.Task task,
                        long durationMS, Throwable problems, boolean hadProblems) {
        int key = System.identityHashCode(executor);
        String captured = labelAtStartMap.remove(key);
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

    /**
     * Extract the {@code runId} field from a pipeline {@code PlaceholderTask} via reflection.
     * {@code PlaceholderTask.runId} equals {@code Run.getExternalizableId()} of the enclosing
     * {@code WorkflowRun}, e.g. {@code "my-pipeline#42"}.
     *
     * <p>Returns {@code null} if the field is absent (not a pipeline task) or inaccessible.
     */
    private static String runIdFromTask(Queue.Task task) {
        try {
            java.lang.reflect.Field f = task.getClass().getDeclaredField("runId");
            f.setAccessible(true);
            Object val = f.get(task);
            if (val instanceof String && !((String) val).isEmpty()) return (String) val;
        } catch (Exception ignored) {
            // Not a PlaceholderTask or runId field not accessible — fall through
        }
        return null;
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
