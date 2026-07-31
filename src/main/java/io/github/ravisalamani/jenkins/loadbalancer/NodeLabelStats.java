package io.github.ravisalamani.jenkins.loadbalancer;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * Ring-buffer of recent build records for one (nodeName, labelExpression) combination.
 * The buffer size is controlled by {@link SmartLBConfig#getHistorySize()} and is applied
 * each time a record is added.
 *
 * <p>Thread-safety: all mutating methods are {@code synchronized}.
 * Read-only accessors snapshot the list to avoid holding the lock.
 */
public final class NodeLabelStats implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String nodeName;
    private final String label;

    /** Newest record first. */
    private final LinkedList<BuildRecord> records = new LinkedList<>();

    public NodeLabelStats(String nodeName, String label) {
        this.nodeName = nodeName;
        this.label    = label;
    }

    public synchronized void addRecord(BuildRecord record, int maxSize) {
        records.addFirst(record);
        while (records.size() > maxSize) {
            records.removeLast();
        }
    }

    public String getNodeName() { return nodeName; }
    public String getLabel()    { return label;    }

    public synchronized List<BuildRecord> getRecords() {
        return Collections.unmodifiableList(new ArrayList<>(records));
    }

    public synchronized int getNodeFaultCount() {
        int count = 0;
        for (BuildRecord r : records) {
            if (r.isNodeFault()) count++;
        }
        return count;
    }

    /** Count of node faults in the most recent {@code threshold} records. */
    public synchronized int getNodeFaultCountInWindow(int threshold) {
        int faults = 0, i = 0;
        for (BuildRecord r : records) {
            if (i >= threshold) break;
            if (r.isNodeFault()) faults++;
            i++;
        }
        return faults;
    }

    /**
     * True when the most recent {@code threshold} records are all node faults.
     * Only looks at the newest {@code threshold} entries so that changing the
     * threshold does not require flushing old history from the buffer.
     */
    public synchronized boolean isSuppressedForScheduling(int threshold) {
        if (records.size() < threshold) return false;
        int faults = 0, i = 0;
        for (BuildRecord r : records) {   // newest first
            if (i >= threshold) break;
            if (r.isNodeFault()) faults++;
            i++;
        }
        return faults >= threshold;
    }

    /** Penalty score contribution from failure history. */
    public synchronized int failurePenalty() {
        return getNodeFaultCount() * 2_000;
    }
}
