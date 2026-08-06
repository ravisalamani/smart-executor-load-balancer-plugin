package io.jenkins.plugins.smartexecutorloadbalancer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class NodeLabelStatsTest {

    private static BuildRecord rec(FailureType type) {
        return new BuildRecord(System.currentTimeMillis(), type, null, 1_000, null, 0.0);
    }

    private static BuildRecord rec(FailureType type, long timestamp) {
        return new BuildRecord(timestamp, type, null, 1_000, null, 0.0);
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

    // ── addRecord dedup (CODE_FAULT → NODE_FAULT replacement) ───────────────

    @Test
    public void nodeFaultReplacesImmediatelyPrecedingCodeFault() {
        // Simulates RunListener (CODE_FAULT) firing just before ExecutorListener (NODE_FAULT)
        // for the same channel-crash event.  The NODE_FAULT should replace the CODE_FAULT.
        NodeLabelStats s = new NodeLabelStats("n", "l");
        long now = System.currentTimeMillis();
        s.addRecord(rec(FailureType.CODE_FAULT, now - 500), 3);
        s.addRecord(rec(FailureType.NODE_FAULT, now),       3);
        List<BuildRecord> recs = s.getRecords();
        assertEquals(1, recs.size(),
                "NODE_FAULT must replace the preceding CODE_FAULT within 10 s");
        assertEquals(FailureType.NODE_FAULT, recs.get(0).getFailureType());
    }

    @Test
    public void nodeFaultDoesNotReplaceOlderCodeFault() {
        // Two independent failures far apart in time — both must be kept.
        NodeLabelStats s = new NodeLabelStats("n", "l");
        long now = System.currentTimeMillis();
        s.addRecord(rec(FailureType.CODE_FAULT, now - 15_000), 3);
        s.addRecord(rec(FailureType.NODE_FAULT, now),          3);
        assertEquals(2, s.getRecords().size(),
                "Old CODE_FAULT must not be replaced by a later NODE_FAULT");
    }

    @Test
    public void suppressionCorrectAfterThreeChannelCrashes() {
        // Verifies that three consecutive channel crashes (each causing one CODE_FAULT + one
        // NODE_FAULT pair within the dedup window) result in exactly three NODE_FAULT entries,
        // triggering suppression at threshold 3.
        NodeLabelStats s = new NodeLabelStats("n", "l");
        long t = System.currentTimeMillis();
        for (int i = 0; i < 3; i++) {
            s.addRecord(rec(FailureType.CODE_FAULT, t - 300), 3);
            s.addRecord(rec(FailureType.NODE_FAULT, t),       3);
            t += 60_000;
        }
        assertTrue(s.isSuppressedForScheduling(3),
                "Three channel crashes must suppress the node at threshold 3");
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
