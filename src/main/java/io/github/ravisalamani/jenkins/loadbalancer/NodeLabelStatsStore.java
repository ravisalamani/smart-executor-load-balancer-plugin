package io.github.ravisalamani.jenkins.loadbalancer;

import hudson.Extension;
import jenkins.model.GlobalConfiguration;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.StaplerRequest2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Logger;

/**
 * Singleton store for all (node, label) statistics.
 *
 * <p>Extends {@link GlobalConfiguration} so Jenkins persists the map
 * automatically to {@code $JENKINS_HOME/io.github.ravisalamani.jenkins.loadbalancer
 * .NodeLabelStatsStore.xml} via XStream.
 */
@Extension
public class NodeLabelStatsStore extends GlobalConfiguration {

    private static final Logger LOGGER =
            Logger.getLogger(NodeLabelStatsStore.class.getName());

    private HashMap<String, NodeLabelStats> statsMap = new HashMap<>();

    public NodeLabelStatsStore() {
        load();
    }

    public static NodeLabelStatsStore get() {
        return GlobalConfiguration.all().get(NodeLabelStatsStore.class);
    }

    public synchronized void addRecord(String nodeName, String label, BuildRecord record) {
        if (nodeName == null || nodeName.isBlank()) return;
        String key = key(nodeName, label == null ? "" : label);
        NodeLabelStats stats = statsMap.get(key);
        if (stats == null) {
            stats = new NodeLabelStats(nodeName, label == null ? "" : label);
            statsMap.put(key, stats);
        }
        SmartLBConfig cfg = SmartLBConfig.get();
        int maxSize = (cfg != null) ? cfg.getFailureThreshold() : 3;
        stats.addRecord(record, maxSize);
        save();
    }

    public synchronized NodeLabelStats get(String nodeName, String label) {
        return statsMap.get(key(nodeName, label == null ? "" : label));
    }

    /** Returns all (node, label) entries sorted by node name then label. */
    public synchronized List<NodeLabelStats> getAllEntries() {
        List<NodeLabelStats> result = new ArrayList<>(statsMap.values());
        result.sort((a, b) -> {
            int c = a.getNodeName().compareTo(b.getNodeName());
            return c != 0 ? c : a.getLabel().compareTo(b.getLabel());
        });
        return result;
    }

    /** Returns all (node, label) entries currently suppressed at the given threshold. */
    public synchronized List<NodeLabelStats> getSuppressedEntries(int threshold) {
        List<NodeLabelStats> result = new ArrayList<>();
        for (NodeLabelStats s : statsMap.values()) {
            if (s.isSuppressedForScheduling(threshold)) result.add(s);
        }
        Collections.sort(result, (a, b) -> {
            int c = a.getNodeName().compareTo(b.getNodeName());
            return c != 0 ? c : a.getLabel().compareTo(b.getLabel());
        });
        return result;
    }

    /** Clears fault history for a single (node, label) pair, re-enabling it immediately. */
    public synchronized void reset(String nodeName, String label) {
        statsMap.remove(key(nodeName, label == null ? "" : label));
        save();
    }

    public synchronized void resetAll() {
        statsMap.clear();
        save();
    }

    @Override
    public boolean configure(StaplerRequest2 req, JSONObject json) {
        return true;
    }

    private static String key(String nodeName, String label) {
        return nodeName + "||" + label;
    }
}
