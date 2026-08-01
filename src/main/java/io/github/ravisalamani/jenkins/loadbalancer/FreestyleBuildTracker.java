package io.github.ravisalamani.jenkins.loadbalancer;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.AbstractBuild;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Catches freestyle build failures where the shell step returns a non-zero
 * exit code without throwing an exception — those do not reach
 * {@link NodeExecutorTracker#taskCompletedWithProblems} because the executor
 * slot itself finishes cleanly.
 *
 * <p>Pipeline (WorkflowRun) failures are intentionally excluded here: each
 * pipeline agent block fires {@link NodeExecutorTracker} independently with
 * stage-level granularity.
 *
 * <p>We only record {@link FailureType#CODE_FAULT} from here.  Node-environment
 * faults that did throw (channel crash, OOM …) were already captured by
 * {@link NodeExecutorTracker#taskCompletedWithProblems}; recording a second
 * CODE_FAULT here for those would undercount the true NODE_FAULT rate.  We
 * guard against double-counting by checking the most-recent record in the store
 * and skipping if it is already a NODE_FAULT for this build.
 */
@Extension
public class FreestyleBuildTracker extends RunListener<AbstractBuild<?, ?>> {

    private static final Logger LOGGER =
            Logger.getLogger(FreestyleBuildTracker.class.getName());

    @Override
    public void onCompleted(AbstractBuild<?, ?> build,
                            @NonNull TaskListener listener) {
        try {
            Result result = build.getResult();
            // ABORTED and NOT_BUILT are not informative for routing decisions.
            if (result == null || result == Result.ABORTED || result == Result.NOT_BUILT) {
                return;
            }

            // Prefer Executor.currentExecutor() — this runs in the executor thread and is
            // more reliable than build.getBuiltOn() when multiple builds complete simultaneously.
            Executor executor = Executor.currentExecutor();
            Node node;
            if (executor != null) {
                Computer computer = executor.getOwner();
                node = computer != null ? computer.getNode() : null;
            } else {
                node = build.getBuiltOn();
            }
            if (node == null) return;

            String nodeName = node.getNodeName();
            if (nodeName == null || nodeName.isBlank()) return;

            NodeLabelStatsStore store = NodeLabelStatsStore.get();
            if (store == null) return;

            double load = SystemLoadMonitor.getLoad(nodeName);

            // Successful build — record positive signal and return.
            if (result == Result.SUCCESS) {
                LOGGER.fine(() -> "SmartLB: recording SUCCESS for freestyle on " + nodeName);
                store.addRecord(nodeName, "",
                        new BuildRecord(System.currentTimeMillis(),
                                FailureType.NONE, null,
                                build.getDuration(), null, load));
                return;
            }

            // Avoid double-counting: if NodeExecutorTracker already recorded a
            // NODE_FAULT for this node within the last 5 seconds, skip.
            NodeLabelStats existing = store.get(nodeName, "");
            if (existing != null) {
                java.util.List<BuildRecord> recs = existing.getRecords();
                if (!recs.isEmpty()) {
                    BuildRecord last = recs.get(0);
                    long ageMs = System.currentTimeMillis() - last.getTimestamp();
                    if (ageMs < 5_000 && last.isNodeFault()) {
                        LOGGER.fine("SmartLB: skipping CODE_FAULT — NODE_FAULT already recorded for "
                                + nodeName);
                        return;
                    }
                }
            }

            LOGGER.fine(() -> "SmartLB: recording CODE_FAULT for freestyle on "
                    + nodeName + " result=" + result);

            store.addRecord(nodeName, "",
                    new BuildRecord(
                            System.currentTimeMillis(),
                            FailureType.CODE_FAULT,
                            "Build result: " + result,
                            build.getDuration(),
                            null,
                            load));

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "SmartLB: error in FreestyleBuildTracker", e);
        }
    }
}
