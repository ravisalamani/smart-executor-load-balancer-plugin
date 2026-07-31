package io.github.ravisalamani.jenkins.loadbalancer;

import org.junit.Test;

import static org.junit.Assert.*;

public class BuildRecordTest {

    private static BuildRecord rec(FailureType type) {
        return new BuildRecord(0, type, null, 0, null, 0.0);
    }

    // ── isNodeFault ──────────────────────────────────────────────────────────

    @Test
    public void nodeFailureIsNodeFault() {
        assertTrue(rec(FailureType.NODE_FAULT).isNodeFault());
    }

    @Test
    public void timeoutIsNodeFault() {
        assertTrue(rec(FailureType.TIMEOUT).isNodeFault());
    }

    @Test
    public void codeFaultIsNotNodeFault() {
        assertFalse(rec(FailureType.CODE_FAULT).isNodeFault());
    }

    @Test
    public void successIsNotNodeFault() {
        assertFalse(rec(FailureType.NONE).isNodeFault());
    }

    @Test
    public void abortedIsNotNodeFault() {
        assertFalse(rec(FailureType.ABORTED).isNodeFault());
    }

    // ── isSuccess ────────────────────────────────────────────────────────────

    @Test
    public void noneIsSuccess() {
        assertTrue(rec(FailureType.NONE).isSuccess());
    }

    @Test
    public void nodeFaultIsNotSuccess() {
        assertFalse(rec(FailureType.NODE_FAULT).isSuccess());
    }

    @Test
    public void codeFaultIsNotSuccess() {
        assertFalse(rec(FailureType.CODE_FAULT).isSuccess());
    }

    // ── getFormattedDuration ─────────────────────────────────────────────────

    @Test
    public void formattedDurationSeconds() {
        BuildRecord r = new BuildRecord(0, FailureType.NONE, null, 45_000, null, 0.0);
        assertEquals("45s", r.getFormattedDuration());
    }

    @Test
    public void formattedDurationMinutesAndSeconds() {
        BuildRecord r = new BuildRecord(0, FailureType.NONE, null, 65_000, null, 0.0);
        assertEquals("1m 5s", r.getFormattedDuration());
    }

    @Test
    public void formattedDurationExactMinutes() {
        BuildRecord r = new BuildRecord(0, FailureType.NONE, null, 120_000, null, 0.0);
        assertEquals("2m 0s", r.getFormattedDuration());
    }
}
