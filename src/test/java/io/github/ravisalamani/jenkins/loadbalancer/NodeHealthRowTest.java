package io.github.ravisalamani.jenkins.loadbalancer;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class NodeHealthRowTest {

    private static BuildRecord rec(FailureType type) {
        return new BuildRecord(0, type, null, 1_000, null, 0.0);
    }

    private static NodeLabelStats statsOf(FailureType... types) {
        NodeLabelStats s = new NodeLabelStats("node1", "linux");
        for (FailureType t : types) s.addRecord(rec(t), 10);
        return s;
    }

    private static SmartLBManagementLink.NodeHealthRow row(NodeLabelStats stats, int threshold) {
        return new SmartLBManagementLink.NodeHealthRow(stats, threshold);
    }

    // ── status classification ────────────────────────────────────────────────

    @Test
    public void allSuccessIsOk() {
        SmartLBManagementLink.NodeHealthRow r =
                row(statsOf(FailureType.NONE, FailureType.NONE, FailureType.NONE), 3);
        assertFalse(r.isSuppressed());
        assertFalse(r.isAtRisk());
        assertEquals("OK", r.getStatusLabel());
    }

    @Test
    public void oneNodeFaultIsAtRisk() {
        SmartLBManagementLink.NodeHealthRow r =
                row(statsOf(FailureType.NONE, FailureType.NONE, FailureType.NODE_FAULT), 3);
        assertFalse(r.isSuppressed());
        assertTrue(r.isAtRisk());
        assertEquals("AT RISK", r.getStatusLabel());
    }

    @Test
    public void twoNodeFaultsIsAtRisk() {
        SmartLBManagementLink.NodeHealthRow r =
                row(statsOf(FailureType.NONE, FailureType.NODE_FAULT, FailureType.NODE_FAULT), 3);
        assertFalse(r.isSuppressed());
        assertTrue(r.isAtRisk());
        assertEquals("AT RISK", r.getStatusLabel());
    }

    @Test
    public void allNodeFaultsIsSuppressed() {
        SmartLBManagementLink.NodeHealthRow r =
                row(statsOf(FailureType.NODE_FAULT, FailureType.NODE_FAULT, FailureType.NODE_FAULT), 3);
        assertTrue(r.isSuppressed());
        assertFalse(r.isAtRisk());
        assertEquals("SUPPRESSED", r.getStatusLabel());
    }

    @Test
    public void codeFaultAloneIsOkNotAtRisk() {
        SmartLBManagementLink.NodeHealthRow r =
                row(statsOf(FailureType.CODE_FAULT, FailureType.CODE_FAULT, FailureType.CODE_FAULT), 3);
        assertFalse(r.isSuppressed());
        assertFalse(r.isAtRisk());
        assertEquals("OK", r.getStatusLabel());
    }

    // ── sparkline ordering ───────────────────────────────────────────────────

    @Test
    public void sparklineIsOldestFirst() {
        NodeLabelStats s = new NodeLabelStats("node1", "linux");
        // add oldest first (addRecord puts newest at front)
        s.addRecord(rec(FailureType.NONE),       3); // oldest
        s.addRecord(rec(FailureType.CODE_FAULT), 3);
        s.addRecord(rec(FailureType.NODE_FAULT), 3); // newest

        List<BuildRecord> sparkline = row(s, 3).getSparkline();

        assertEquals(3, sparkline.size());
        assertEquals(FailureType.NONE,       sparkline.get(0).getFailureType()); // oldest → left
        assertEquals(FailureType.CODE_FAULT, sparkline.get(1).getFailureType());
        assertEquals(FailureType.NODE_FAULT, sparkline.get(2).getFailureType()); // newest → right
    }

    @Test
    public void sparklineSizeMatchesRecordCount() {
        NodeLabelStats s = statsOf(FailureType.NONE, FailureType.NONE);
        assertEquals(2, row(s, 3).getSparkline().size());
    }
}
