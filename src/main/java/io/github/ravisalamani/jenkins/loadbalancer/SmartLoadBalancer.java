package io.github.ravisalamani.jenkins.loadbalancer;

import hudson.model.Computer;
import hudson.model.Job;
import hudson.model.LoadBalancer;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.queue.MappingWorksheet;
import hudson.model.queue.MappingWorksheet.ExecutorChunk;
import hudson.model.queue.MappingWorksheet.Mapping;
import hudson.model.queue.MappingWorksheet.WorkChunk;
import jenkins.model.Jenkins;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Smart load balancer — v2.
 *
 * <h3>Scoring formula (higher = more preferred)</h3>
 * <pre>
 *   score = (idleExecutors  × 1 000)
 *         − (busyExecutors  × 10 000)   [from v1 — any busy exec heavily penalised]
 *         − (sysLoadAvg     × loadWeight) [e.g., load 4.0 × 200 = −800]
 *         − (nodeFaultCount × 2 000)    [per node-env fault in the last 10 runs]
 * </pre>
 *
 * <h3>Hard avoidance</h3>
 * When {@code nodeFaultCount ≥ failureThreshold} (default 10) the node is
 * excluded entirely for that label.  If ALL candidate nodes are suppressed the
 * balancer falls back to normal scoring (so builds never get stuck forever).
 * The admin can also suppress a node manually from the management UI.
 */
public class SmartLoadBalancer extends LoadBalancer {

    private static final Logger LOGGER =
            Logger.getLogger(SmartLoadBalancer.class.getName());

    /** Previous balancer — used as fallback for opt-out jobs and on exceptions. */
    private final LoadBalancer fallback;

    public SmartLoadBalancer() {
        this(LoadBalancer.DEFAULT);
    }

    public SmartLoadBalancer(LoadBalancer fallback) {
        this.fallback = fallback != null ? fallback : LoadBalancer.DEFAULT;
    }

    /**
     * Return {@code this} so that {@code queue.getLoadBalancer()} reports
     * {@code SmartLoadBalancer} rather than the anonymous {@code $2} wrapper
     * Jenkins normally creates. We replicate the quiet-down guard here.
     */
    @Override
    protected LoadBalancer sanitize() {
        return this;
    }

    @Override
    public Mapping map(Queue.Task task, MappingWorksheet ws) {
        if (Jenkins.get().isQuietingDown()) {
            return null;
        }
        // Per-job opt-out: delegate entirely to the previous balancer
        if (isDisabledForTask(task)) {
            LOGGER.fine(() -> "SmartLB: opt-out set for " + task.getFullDisplayName()
                    + ", delegating to fallback balancer");
            return fallback.map(task, ws);
        }

        try {
            return smartMap(task, ws);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING,
                    "SmartLB: scoring failed for " + task.getFullDisplayName()
                    + ", falling back to default balancer", e);
            return fallback.map(task, ws);
        }
    }

    private Mapping smartMap(Queue.Task task, MappingWorksheet ws) {
        Mapping m = ws.new Mapping();

        for (int i = 0; i < ws.works.size(); i++) {
            WorkChunk wc = ws.works.get(i);
            ExecutorChunk best = findBestExecutorChunk(task, wc);
            if (best == null) return null;
            m.assign(i, best);
        }

        return m.isCompletelyValid() ? m : null;
    }

    /**
     * Walks up the owner-task chain to find a Job and checks whether the
     * SmartLBJobProperty opt-out is set.  Works for both freestyle and
     * Pipeline PlaceholderTask (which reports its WorkflowJob as owner).
     */
    private static boolean isDisabledForTask(Queue.Task task) {
        Queue.Task t = task;
        while (t != null) {
            if (t instanceof Job) {
                SmartLBJobProperty prop =
                        ((Job<?, ?>) t).getProperty(SmartLBJobProperty.class);
                return prop != null && prop.isDisableSmartLB();
            }
            Queue.Task owner = t.getOwnerTask();
            if (owner == t) break;
            t = owner;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Candidate selection
    // -------------------------------------------------------------------------

    private ExecutorChunk findBestExecutorChunk(Queue.Task task, WorkChunk wc) {
        NodeLabelStatsStore store = NodeLabelStatsStore.get();
        SmartLBConfig       config = SmartLBConfig.get();

        int threshold = (config != null) ? config.getFailureThreshold() : 10;
        boolean skipFailing = (config == null) || config.isSkipFailingNodes();

        List<ExecutorChunk> allCandidates = new ArrayList<>(wc.applicableExecutorChunks());
        if (allCandidates.isEmpty()) return null;

        // Partition into active (allowed) and suppressed
        List<ExecutorChunk> active     = new ArrayList<>();
        List<ExecutorChunk> suppressed = new ArrayList<>();

        for (ExecutorChunk ec : allCandidates) {
            if (skipFailing && isSuppressed(ec, store, threshold)) {
                suppressed.add(ec);
                logSuppression(ec, store, threshold);
            } else {
                active.add(ec);
            }
        }

        // Fall back to suppressed nodes only when nothing else is available
        List<ExecutorChunk> pool = active.isEmpty() ? allCandidates : active;

        pool.sort(Comparator.comparingInt(
                (ExecutorChunk ec) -> score(ec, store, config)).reversed());

        ExecutorChunk chosen = pool.get(0);
        LOGGER.fine(() -> String.format(
                "SmartLB: chose %s (score=%d) — active=%d suppressed=%d",
                chosen.getName(), score(chosen, store, config),
                active.size(), suppressed.size()));
        return chosen;
    }

    // -------------------------------------------------------------------------
    // Scoring
    // -------------------------------------------------------------------------

    private int score(ExecutorChunk ec, NodeLabelStatsStore store, SmartLBConfig config) {
        Computer computer = ec.computer;
        if (computer == null) return Integer.MIN_VALUE;

        int idle = computer.countIdle();
        int busy = computer.countBusy();

        // Base executor balance
        int s = (idle * 1_000) - (busy * 10_000);

        // System load penalty
        double sysLoad  = SystemLoadMonitor.getLoad(computer.getName());
        int loadWeight  = (config != null) ? config.getLoadWeight() : 200;
        s -= (int) (sysLoad * loadWeight);

        // Failure-history penalty (per-node)
        if (store != null) {
            Node node = computer.getNode();
            if (node != null) {
                NodeLabelStats stats = store.get(node.getNodeName(), "");
                if (stats != null) {
                    s -= stats.failurePenalty();
                }
            }
        }

        return s;
    }

    private boolean isSuppressed(ExecutorChunk ec, NodeLabelStatsStore store, int threshold) {
        if (store == null || ec.computer == null) return false;
        Node node = ec.computer.getNode();
        if (node == null) return false;
        NodeLabelStats stats = store.get(node.getNodeName(), "");
        return stats != null && stats.isSuppressedForScheduling(threshold);
    }

    private void logSuppression(ExecutorChunk ec, NodeLabelStatsStore store, int threshold) {
        if (ec.computer == null) return;
        Node node = ec.computer.getNode();
        if (node == null) return;
        NodeLabelStats stats = store == null ? null : store.get(node.getNodeName(), "");
        LOGGER.info(() -> String.format(
                "SmartLB: SKIPPING %s — %d/%d node faults",
                node.getNodeName(),
                stats != null ? stats.getNodeFaultCount() : 0, threshold));
    }

}

