package io.jenkins.plugins.smartexecutorloadbalancer;

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
 * <p>When a freestyle build fails due to an infrastructure fault (channel crash,
 * OOM …), both this listener and {@link NodeExecutorTracker#taskCompletedWithProblems}
 * fire for the same event — {@code RunListener} always fires first.  This class
 * records {@code CODE_FAULT} unconditionally; {@link NodeLabelStats#addRecord}
 * replaces it with the subsequent {@code NODE_FAULT} when the two arrive within
 * 10 seconds of each other.
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
                node = executor.getOwner().getNode(); // getOwner() is @NonNull
            } else {
                node = build.getBuiltOn();
            }
            if (node == null) return;

            String nodeName = node.getNodeName();
            if (nodeName.isBlank()) return;

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
