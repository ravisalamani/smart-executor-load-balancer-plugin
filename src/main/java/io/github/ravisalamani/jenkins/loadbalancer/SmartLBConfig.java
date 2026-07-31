package io.github.ravisalamani.jenkins.loadbalancer;

import hudson.Extension;
import jenkins.model.GlobalConfiguration;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest;

/**
 * Persistent configuration for the Smart Load Balancer.
 * Settings are edited on the Smart LB management page, not System Configuration.
 */
@Extension
public class SmartLBConfig extends GlobalConfiguration {

    @Override
    public String getDisplayName() {
        return null; // hides this section from Manage Jenkins → System Configuration
    }

    /**
     * Consecutive-fault window and avoidance threshold in one number.
     * The balancer looks at the last {@code failureThreshold} builds for each
     * (node, label) pair and suppresses the node when ALL of them are node faults.
     */
    private int failureThreshold = 3;

    /**
     * When true the balancer excludes nodes that hit the threshold.
     * Defaults to false so admins explicitly opt in to node avoidance.
     */
    private boolean skipFailingNodes = false;

    /**
     * Weight applied to the 1-minute system load average.
     * A load of 4.0 subtracts {@code 4.0 × loadWeight} from the score.
     */
    private int loadWeight = 200;

    public SmartLBConfig() {
        load();
    }

    public static SmartLBConfig get() {
        return GlobalConfiguration.all().get(SmartLBConfig.class);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public int getFailureThreshold()     { return failureThreshold; }
    public boolean isSkipFailingNodes()  { return skipFailingNodes; }
    public int getLoadWeight()           { return loadWeight; }

    @DataBoundSetter
    public void setFailureThreshold(int v) {
        this.failureThreshold = Math.max(1, v);
        save();
    }

    @DataBoundSetter
    public void setSkipFailingNodes(boolean v) {
        this.skipFailingNodes = v;
        save();
    }

    @DataBoundSetter
    public void setLoadWeight(int v) {
        this.loadWeight = Math.max(0, v);
        save();
    }

    // -------------------------------------------------------------------------
    // GlobalConfiguration
    // -------------------------------------------------------------------------

    @Override
    public boolean configure(StaplerRequest req, JSONObject json) {
        req.bindJSON(this, json);
        save();
        return true;
    }
}
