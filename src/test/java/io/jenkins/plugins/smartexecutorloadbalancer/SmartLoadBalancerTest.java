package io.jenkins.plugins.smartexecutorloadbalancer;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.slaves.DumbSlave;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link SmartLoadBalancer}.
 *
 * <p>Topology:
 * <pre>
 *   node1  – 4 executors, label "linux node1" – 1 executor occupied by a blocking build
 *   node2  – 4 executors, label "linux node2" – all idle
 *   node3  – 4 executors, label "linux node3" – all idle
 *   node4  – 4 executors, label "linux node4" – all idle
 * </pre>
 * Expected: a build queued for label {@code linux} is assigned to node2/3/4.
 * <pre>
 *   node1 score = (3×1 000) − (1×10 000) = −7 000
 *   node2/3/4 score = (4×1 000) − (0×10 000) = +4 000
 * </pre>
 */
@WithJenkins
public class SmartLoadBalancerTest {

    @Test
    public void balancerInstantiates(JenkinsRule j) {
        assertNotNull(new SmartLoadBalancer());
    }

    @Test
    public void schedulingPrefersNodeWithMoreIdleExecutors(JenkinsRule j) throws Exception {
        j.jenkins.getQueue().setLoadBalancer(new SmartLoadBalancer());

        DumbSlave node1 = createSlave(j, "node1", "linux node1", 4);
        createSlave(j, "node2", "linux node2", 4);
        createSlave(j, "node3", "linux node3", 4);
        createSlave(j, "node4", "linux node4", 4);

        CountDownLatch buildRunning   = new CountDownLatch(1);
        CountDownLatch buildCanFinish = new CountDownLatch(1);

        FreeStyleProject blockingProject = j.createFreeStyleProject("node1-blocker");
        blockingProject.setAssignedLabel(Label.get("node1"));
        blockingProject.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher,
                                   BuildListener listener)
                    throws InterruptedException, IOException {
                buildRunning.countDown();
                buildCanFinish.await();
                return true;
            }
        });

        blockingProject.scheduleBuild2(0).waitForStart();
        assertTrue(buildRunning.await(30, TimeUnit.SECONDS),
                "Blocking build should start within 30 s");

        try {
            FreeStyleProject testProject = j.createFreeStyleProject("linux-test");
            testProject.setAssignedLabel(Label.get("linux"));

            FreeStyleBuild testBuild = j.buildAndAssertSuccess(testProject);

            String builtOn = testBuild.getBuiltOnStr();
            assertNotEquals("node1", builtOn,
                    "Smart balancer should prefer a freer node when node1 has a running build");

            System.out.println("[TEST] linux build correctly ran on: " + builtOn);
        } finally {
            buildCanFinish.countDown();
        }
    }

    private DumbSlave createSlave(JenkinsRule j, String name, String labels, int numExecutors)
            throws Exception {
        DumbSlave slave = j.createSlave(name, labels, null);
        slave.setNumExecutors(numExecutors);
        j.waitOnline(slave);
        return slave;
    }
}
