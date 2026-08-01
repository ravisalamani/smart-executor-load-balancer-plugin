package io.github.ravisalamani.jenkins.loadbalancer;

import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.slaves.DumbSlave;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that NodeExecutorTracker never leaks entries in its per-executor maps.
 *
 * <p>Regression for the bug where taskCompleted() returned early for freestyle builds
 * (task instanceof AbstractProject) without removing the entries that taskStarted()
 * had added to loadAtStartMap and labelAtStartMap.
 */
@WithJenkins
class NodeExecutorTrackerTest {

    /**
     * After a freestyle build completes, both per-executor maps must be empty.
     *
     * <p>The original bug: taskCompleted() contained {@code if (task instanceof AbstractProject) return;}
     * with no prior cleanup, so every freestyle build left one stale entry in each map.
     */
    @Test
    void freestyleMapsEmptyAfterSuccessfulBuild(JenkinsRule j) throws Exception {
        DumbSlave agent = j.createSlave("node-leak-test", "leak-test-label", null);
        j.waitOnline(agent);

        FreeStyleProject project = j.createFreeStyleProject("leak-test-job");
        project.setAssignedLabel(Label.get("leak-test-label"));

        j.buildAndAssertSuccess(project);
        j.buildAndAssertSuccess(project);
        j.buildAndAssertSuccess(project);

        NodeExecutorTracker tracker =
                j.jenkins.getExtensionList(NodeExecutorTracker.class).get(0);

        assertEquals(0, mapSize(tracker, "loadAtStartMap"),
                "loadAtStartMap must be empty after builds complete — memory leak if non-zero");
        assertEquals(0, mapSize(tracker, "labelAtStartMap"),
                "labelAtStartMap must be empty after builds complete — memory leak if non-zero");
        assertEquals(0, mapSize(tracker, "runIdAtStartMap"),
                "runIdAtStartMap must be empty after builds complete");
    }

    @SuppressWarnings("unchecked")
    private int mapSize(NodeExecutorTracker tracker, String field) throws Exception {
        Field f = NodeExecutorTracker.class.getDeclaredField(field);
        f.setAccessible(true);
        return ((Map<?, ?>) f.get(tracker)).size();
    }
}
