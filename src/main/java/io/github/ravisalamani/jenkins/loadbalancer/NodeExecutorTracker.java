package io.github.ravisalamani.jenkins.loadbalancer;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.ExecutorListener;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.OneOffExecutor;
import hudson.model.Queue;
import hudson.slaves.ComputerListener;
import hudson.slaves.OfflineCause;
import org.jenkinsci.plugins.workflow.actions.ErrorAction;
import org.jenkinsci.plugins.workflow.graph.BlockEndNode;
import org.jenkinsci.plugins.workflow.graph.BlockStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.support.steps.ExecutorStepExecution;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
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
 * <p>For pipeline {@code node()} blocks the outcome is determined per executor slot by
 * inspecting the {@link BlockStartNode} returned by
 * {@link ExecutorStepExecution.PlaceholderTask#getNode()}.  At {@link #taskCompleted} we look up
 * the corresponding {@link BlockEndNode}: if it carries an {@link ErrorAction} the script inside
 * the block failed → {@link FailureType#CODE_FAULT}; no {@code ErrorAction} means the slot exited
 * cleanly → {@link FailureType#NONE}.
 * Infrastructure failures (channel crash, OOM, agent timeout) can surface via
 * {@code taskCompletedWithProblems} → {@link FailureType#NODE_FAULT}, but for JNLP agents
 * this callback never fires because executor threads run in the agent JVM and die with it.
 * {@link #onTemporarilyOffline} (a {@link ComputerListener} hook) fires on the controller the moment
 * the agent channel closes; any executor still holding a task at that point is recorded
 * as {@link FailureType#NODE_FAULT} directly.  A guard set prevents double-recording if
 * {@code taskCompleted} later fires for the same executor.
 *
 * <p>Parallel pipelines are handled correctly: each {@code node()} block has its own
 * {@link BlockStartNode}, so a failing branch only marks the agent that ran that branch as
 * {@code CODE_FAULT}; healthy parallel agents are unaffected.
 *
 * <p>{@link Queue.FlyweightTask} tasks (internal housekeeping) and {@link OneOffExecutor}
 * tasks (workspace cleanup, etc.) are excluded: they are not real builds and should not
 * influence node-health scoring.
 */
@Extension
public class NodeExecutorTracker extends ComputerListener implements ExecutorListener {

    private static final Logger LOGGER =
            Logger.getLogger(NodeExecutorTracker.class.getName());

    /** executor object → system load captured at task start. */
    final ConcurrentHashMap<Executor, Double> loadAtStartMap = new ConcurrentHashMap<>();

    /**
     * Executors already recorded via {@link #onOffline} — prevents double-counting if
     * {@code taskCompleted} happens to fire for the same executor afterward.
     */
    private final Set<Executor> offlineHandled = ConcurrentHashMap.newKeySet();

    /**
     * executor object → label expression captured at task start.
     *
     * <p>Captured at start because {@code PlaceholderTask.getAssignedLabel()} may change from
     * the requested label (e.g. "fib_linux") to the actual node name once the task is dispatched.
     * Using the Executor object itself as key avoids identity-hash collisions between concurrent
     * executors.
     */
    final ConcurrentHashMap<Executor, String> labelAtStartMap = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // ExecutorListener callbacks
    // -------------------------------------------------------------------------

    @Override
    public void taskStarted(Executor executor, Queue.Task task) {
        if (task instanceof Queue.FlyweightTask || executor instanceof OneOffExecutor) return;
        try {
            Computer computer = executor.getOwner();
            double load = SystemLoadMonitor.getLoad(computer.getName());
            loadAtStartMap.put(executor, load);
            labelAtStartMap.put(executor, labelExpr(task));
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "SmartLB: error capturing start state", e);
        }
    }

    @Override
    public void taskCompletedWithProblems(Executor executor, Queue.Task task,
                                          long durationMS, Throwable problems) {
        if (task instanceof Queue.FlyweightTask || executor instanceof OneOffExecutor) return;
        offlineHandled.remove(executor);
        try {
            FailureType type;
            String reason;
            if (problems != null) {
                type   = FailureClassifier.classify(problems);
                reason = FailureClassifier.extractReason(problems);
                LOGGER.fine(() -> String.format(
                        "SmartLB: executor fault on %s: %s — %s",
                        nodeNameOf(executor), type, reason));
            } else {
                type   = FailureType.UNKNOWN;
                reason = "taskCompletedWithProblems: no exception provided";
            }
            record(executor, task, durationMS, type, reason);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "SmartLB: error recording executor fault", e);
        }
    }

    @Override
    public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
        if (task instanceof Queue.FlyweightTask || executor instanceof OneOffExecutor) return;
        if (offlineHandled.remove(executor)) return;
        if (task instanceof AbstractProject) {
            // FreestyleBuildTracker handles recording for freestyle; just clean up maps.
            loadAtStartMap.remove(executor);
            labelAtStartMap.remove(executor);
            return;
        }
        try {
            if (task instanceof ExecutorStepExecution.PlaceholderTask) {
                FlowNode fn = ((ExecutorStepExecution.PlaceholderTask) task).getNode();
                if (fn instanceof BlockStartNode) {
                    // CPS execution writes the BlockEndNode asynchronously after releasing the
                    // executor — getEndNode() returns null if called immediately.  Capture all
                    // executor-bound data now (maps are keyed by Executor identity) and defer
                    // the failure-type inspection by 500 ms so the BlockEndNode is present.
                    labelAtStartMap.remove(executor);
                    Double loadBoxed = loadAtStartMap.remove(executor);
                    Computer computer = executor.getOwner();
                    Node node = computer.getNode();
                    if (node == null) return;
                    String nodeName = node.getNodeName();
                    if (nodeName == null || nodeName.isBlank()) return;
                    final String stageName = stageNameFrom(task);
                    final double load     = loadBoxed != null ? loadBoxed : 0.0;
                    final BlockStartNode startNode = (BlockStartNode) fn;

                    jenkins.util.Timer.get().schedule(() -> {
                        try {
                            FailureType type = FailureType.NONE;
                            String reason = null;
                            BlockEndNode end = startNode.getEndNode();
                            if (end != null) {
                                ErrorAction err = end.getAction(ErrorAction.class);
                                if (err != null) {
                                    Throwable t = err.getError();
                                    FailureType classified = FailureClassifier.classify(t);
                                    // ABORTED = user action; TIMEOUT = pipeline timeout step —
                                    // neither penalises the node. UNKNOWN falls back to CODE_FAULT.
                                    type = switch (classified) {
                                        case NODE_FAULT -> FailureType.NODE_FAULT;
                                        case TIMEOUT    -> FailureType.TIMEOUT;
                                        case CODE_FAULT -> FailureType.CODE_FAULT;
                                        case ABORTED    -> FailureType.NONE;
                                        default         -> FailureType.CODE_FAULT;
                                    };
                                    reason = FailureClassifier.extractReason(t);
                                    final FailureType ft = type;
                                    final String fr = reason;
                                    LOGGER.fine(() -> String.format(
                                            "SmartLB: %s on %s (pipeline): %s", ft, nodeName, fr));
                                }
                            }
                            NodeLabelStatsStore store = NodeLabelStatsStore.get();
                            if (store != null) {
                                store.addRecord(nodeName, "",
                                        new BuildRecord(System.currentTimeMillis(),
                                                type, reason, durationMS, stageName, load));
                            }
                        } catch (Exception e) {
                            LOGGER.log(Level.WARNING, "SmartLB: deferred pipeline record failed", e);
                        }
                    }, 500, TimeUnit.MILLISECONDS);
                    return;
                }
            }
            record(executor, task, durationMS, FailureType.NONE, null);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "SmartLB: error recording executor completion", e);
        }
    }

    // -------------------------------------------------------------------------
    // ComputerListener — detect executors lost when JNLP agent channel dies
    // -------------------------------------------------------------------------

    @Override
    public void onOffline(@NonNull Computer computer, @CheckForNull OfflineCause cause) {
        // JNLP inbound agents fire onOffline (via Computer.disconnect()) when the channel
        // closes; SSH agents fire onTemporarilyOffline. Handle both.
        recordChannelFault(computer, cause);
    }

    @Override
    public void onTemporarilyOffline(@NonNull Computer computer, @CheckForNull OfflineCause cause) {
        recordChannelFault(computer, cause);
    }

    private void recordChannelFault(@NonNull Computer computer, @CheckForNull OfflineCause cause) {
        // Only react to unexpected channel terminations (container killed, network drop, OOM).
        // Admin-initiated disconnects (UserCause, ByCLI) are intentional and not node faults.
        if (!(cause instanceof OfflineCause.ChannelTermination)) return;

        Node node = computer.getNode();
        if (node == null) return;
        String nodeName = node.getNodeName();
        if (nodeName == null || nodeName.isBlank()) return;

        String msg = cause.getMessage();
        String offlineReason = msg.isEmpty()
                ? "Agent channel terminated unexpectedly"
                : "Agent channel terminated unexpectedly: " + msg;

        for (Executor executor : computer.getExecutors()) {
            Queue.Executable executable = executor.getCurrentExecutable();
            if (executable == null) continue;

            // FreestyleBuildTracker handles AbstractProject via RunListener — skip them here.
            Queue.Task task = null;
            try {
                task = executable.getParent().getOwnerTask();
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "SmartLB: could not resolve owner task", e);
            }
            if (task instanceof AbstractProject) continue;

            String captured = labelAtStartMap.remove(executor);
            Double loadBoxed = loadAtStartMap.remove(executor);
            double load = loadBoxed != null ? loadBoxed : 0.0;
            String stageName = stageNameFrom(task);

            offlineHandled.add(executor);

            final String ln = nodeName;
            final String reason = offlineReason;
            LOGGER.info(() -> String.format(
                    "SmartLB: NODE_FAULT on %s (agent offline mid-build): %s", ln, reason));

            NodeLabelStatsStore store = NodeLabelStatsStore.get();
            if (store != null) {
                store.addRecord(nodeName, "",
                        new BuildRecord(System.currentTimeMillis(),
                                FailureType.NODE_FAULT, reason, 0L, stageName, load));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void record(Executor executor, Queue.Task task, long durationMS,
                        FailureType type, @Nullable String reason) {
        labelAtStartMap.remove(executor);
        Double loadBoxed = loadAtStartMap.remove(executor);

        Computer computer = executor.getOwner();
        Node node = computer.getNode();
        if (node == null) return;

        String nodeName = node.getNodeName();
        if (nodeName == null || nodeName.isBlank()) return;

        String stageName = stageNameFrom(task);
        double load = loadBoxed != null ? loadBoxed : 0.0;

        NodeLabelStatsStore store = NodeLabelStatsStore.get();
        if (store != null) {
            store.addRecord(nodeName, "",
                    new BuildRecord(System.currentTimeMillis(),
                            type, reason, durationMS, stageName, load));
        }
    }

    private static String nodeNameOf(Executor executor) {
        try {
            Node n = executor.getOwner().getNode();
            return n != null ? n.getNodeName() : "?";
        } catch (Exception e) {
            return "?";
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
