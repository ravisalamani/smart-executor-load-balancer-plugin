package io.github.ravisalamani.jenkins.loadbalancer;

import hudson.Extension;
import hudson.model.ManagementLink;
import hudson.model.RootAction;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Adds a "Smart Load Balancer" entry to Manage Jenkins for plugin configuration.
 *
 * <p>URL: {@code /manage/smart-lb/}
 */
@Extension
public class SmartLBManagementLink extends ManagementLink implements RootAction {

    @Override
    public String getIconFileName() {
        return "symbol-analytics-outline plugin-ionicons-api";
    }

    @Override
    public String getDisplayName() {
        return "Smart Executor Load Balancer";
    }

    @Override
    public String getDescription() {
        return "Configure fault-aware executor load balancing settings.";
    }

    @Override
    public String getUrlName() {
        return "smart-lb";
    }

    public SmartLBConfig getConfig() {
        return SmartLBConfig.get();
    }

    /** Used by index.jelly to render the full node-health table. */
    public List<NodeHealthRow> getAllNodes() {
        NodeLabelStatsStore store = NodeLabelStatsStore.get();
        SmartLBConfig cfg = SmartLBConfig.get();
        if (store == null || cfg == null) return Collections.emptyList();
        int threshold = cfg.getFailureThreshold();
        List<NodeHealthRow> rows = new ArrayList<>();
        for (NodeLabelStats s : store.getAllEntries()) {
            rows.add(new NodeHealthRow(s, threshold));
        }
        return rows;
    }

    /** View model for one row in the node-health table. */
    public static final class NodeHealthRow {
        private final NodeLabelStats stats;
        private final boolean suppressed;
        private final boolean atRisk;

        public NodeHealthRow(NodeLabelStats stats, int threshold) {
            this.stats      = stats;
            this.suppressed = stats.isSuppressedForScheduling(threshold);
            this.atRisk     = !suppressed && stats.getNodeFaultCountInWindow(threshold) > 0;
        }

        public NodeLabelStats getStats()    { return stats; }
        public boolean isSuppressed()       { return suppressed; }
        public boolean isAtRisk()           { return atRisk; }

        public String getStatusLabel() {
            if (suppressed) return "SUPPRESSED";
            if (atRisk)     return "AT RISK";
            return "OK";
        }

        public String getStatusStyle() {
            if (suppressed) return "color:var(--danger-color);font-weight:bold";
            if (atRisk)     return "color:var(--warning-color);font-weight:bold";
            return "color:var(--success-color)";
        }

        /** Records in oldest-first order for left-to-right sparkline rendering. */
        public List<BuildRecord> getSparkline() {
            List<BuildRecord> recs = new ArrayList<>(stats.getRecords());
            Collections.reverse(recs);
            return recs;
        }
    }

    @POST
    public HttpResponse doConfigure(@QueryParameter int failureThreshold,
                                    @QueryParameter boolean skipFailingNodes,
                                    @QueryParameter int loadWeight) {
        Jenkins.get().checkPermission(Jenkins.MANAGE);
        SmartLBConfig cfg = SmartLBConfig.get();
        if (cfg != null) {
            cfg.setFailureThreshold(failureThreshold);
            cfg.setSkipFailingNodes(skipFailingNodes);
            cfg.setLoadWeight(loadWeight);
        }
        return HttpResponses.redirectToDot();
    }

    @POST
    public HttpResponse doResetNode(@QueryParameter String nodeName,
                                    @QueryParameter String label) {
        Jenkins.get().checkPermission(Jenkins.MANAGE);
        NodeLabelStatsStore store = NodeLabelStatsStore.get();
        if (store != null) store.reset(nodeName, label);
        return HttpResponses.redirectToDot();
    }

    @POST
    public HttpResponse doResetStats() {
        Jenkins.get().checkPermission(Jenkins.MANAGE);
        NodeLabelStatsStore store = NodeLabelStatsStore.get();
        if (store != null) store.resetAll();
        return HttpResponses.redirectToDot();
    }
}
