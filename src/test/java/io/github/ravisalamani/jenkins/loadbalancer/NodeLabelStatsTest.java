package io.github.ravisalamani.jenkins.loadbalancer;

import org.junit.Test;

import static org.junit.Assert.*;

public class NodeLabelStatsTest {

    private static BuildRecord rec(FailureType type) {
        return new BuildRecord(System.currentTimeMillis(), type, null, 1_000, null, 0.0);
    }

    // ── ring buffer ─────────────────────────────────────────────────────────

    @Test
    public void ringBufferTrimsToMaxSize() {
        NodeLabelStats s = new NodeLabelStats("n", "l");
        for (int i = 0; i < 5; i++) s.addRecord(rec(FailureType.NONE), 3);
        assertEquals(3, s.getRecords().size());
    }

    @Test
    public void ringBufferKeepsNewestRecord() {
        NodeLabelStats s = new NodeLabelStats("n", "l");
        s.addRecord(rec(FailureType.NONE),       3); // oldest — dropped
        s.addRecord(rec(FailureType.NONE),       3);
        s.addRecord(rec(FailureType.NONE),       3);
        s.addRecord(rec(FailureType.NODE_FAULT), 3); // newest
        assertEquals(FailureType.NODE_FAULT, s.getRecords().get(0).getFailureType());
    }

    // ── isSuppressedForScheduling ────────────────────────────────────────────

    @Test
    public void notSuppressedWhenFewerRecordsThanThreshold() {
        NodeLabelStats s = new NodeLabelStats("n", "l");
        s.addRecord(rec(FailureType.NODE_FAULT), 3);
        s.addRecord(rec(FailureType.NODE_FAULT), 3);
        assertFalse(s.isSuppressedForScheduling(3));
    }

    @Test
    public void suppressedWhenAllThresholdRecordsAreNodeFaults() {
        NodeLabelStats s = new NodeLabelStats("n", "l");
        s.addRecord(rec(FailureType.NODE_FAULT), 3);
        s.addRecord(rec(FailureType.NODE_FAULT), 3);
        s.addRecord(rec(FailureType.NODE_FAULT), 3);
        assertTrue(s.isSuppressedForScheduling(3));
    }

    @Test
    public void notSuppressedWhenOneSuccessInWindow() {
        NodeLabelStats s = new NodeLabelStats("n", "l");
        s.addRecord(rec(FailureType.NONE),       3);
        s.addRecord(rec(FailureType.NODE_FAULT), 3);
        s.addRecord(rec(FailureType.NODE_FAULT), 3);
        assertFalse(s.isSuppressedForScheduling(3));
    }

    @Test
    public void codeFaultDoesNotTriggerSuppression() {
        NodeLabelStats s = new NodeLabelStats("n", "l");
        s.addRecord(rec(FailureType.CODE_FAULT), 3);
        s.addRecord(rec(FailureType.CODE_FAULT), 3);
        s.addRecord(rec(FailureType.CODE_FAULT), 3);
        assertFalse(s.isSuppressedForScheduling(3));
    }

    @Test
    public void timeoutCountsTowardSuppression() {
        NodeLabelStats s = new NodeLabelStats("n", "l");
        s.addRecord(rec(FailureType.TIMEOUT),    3);
        s.addRecord(rec(FailureType.TIMEOUT),    3);
        s.addRecord(rec(FailureType.TIMEOUT),    3);
        assertTrue(s.isSuppressedForScheduling(3));
    }

    @Test
    public void mixOfNodeFaultAndTimeoutSuppresses() {
        NodeLabelStats s = new NodeLabelStats("n", "l");
        s.addRecord(rec(FailureType.NODE_FAULT), 3);
        s.addRecord(rec(FailureType.TIMEOUT),    3);
        s.addRecord(rec(FailureType.NODE_FAULT), 3);
        assertTrue(s.isSuppressedForScheduling(3));
    }

    @Test
    public void suppressionOnlyLooksAtMostRecentWindow() {
        NodeLabelStats s = new NodeLabelStats("n", "l");
        // old success — outside the window of 3
        s.addRecord(rec(FailureType.NONE),       5);
        s.addRecord(rec(FailureType.NODE_FAULT), 5);
        s.addRecord(rec(FailureType.NODE_FAULT), 5);
        s.addRecord(rec(FailureType.NODE_FAULT), 5);
        // last 3 are all NODE_FAULT → suppressed
        assertTrue(s.isSuppressedForScheduling(3));
    }

    // ── getNodeFaultCountInWindow ────────────────────────────────────────────

    @Test
    public void nodeFaultCountInWindowIsCorrect() {
        NodeLabelStats s = new NodeLabelStats("n", "l");
        s.addRecord(rec(FailureType.NONE),       3);
        s.addRecord(rec(FailureType.NODE_FAULT), 3);
        s.addRecord(rec(FailureType.NODE_FAULT), 3);
        assertEquals(2, s.getNodeFaultCountInWindow(3));
    }

    @Test
    public void nodeFaultCountRespectsThresholdBoundary() {
        NodeLabelStats s = new NodeLabelStats("n", "l");
        for (int i = 0; i < 5; i++) s.addRecord(rec(FailureType.NODE_FAULT), 5);
        // only look at 3 most recent
        assertEquals(3, s.getNodeFaultCountInWindow(3));
    }

    @Test
    public void codeFaultNotCountedInWindow() {
        NodeLabelStats s = new NodeLabelStats("n", "l");
        s.addRecord(rec(FailureType.CODE_FAULT), 3);
        s.addRecord(rec(FailureType.CODE_FAULT), 3);
        s.addRecord(rec(FailureType.CODE_FAULT), 3);
        assertEquals(0, s.getNodeFaultCountInWindow(3));
    }
}
