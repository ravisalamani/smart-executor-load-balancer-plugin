package io.github.ravisalamani.jenkins.loadbalancer;

import hudson.Extension;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Per-job opt-out from Smart Load Balancer scheduling.
 *
 * <p>When enabled on a job, {@link SmartLoadBalancer} delegates that job's
 * scheduling entirely to the fallback (Jenkins default) balancer. Useful for
 * jobs that must run on a specific node regardless of fault history or load.
 */
public class SmartLBJobProperty extends JobProperty<Job<?, ?>> {

    private boolean disableSmartLB;

    @DataBoundConstructor
    public SmartLBJobProperty() {}

    public boolean isDisableSmartLB() {
        return disableSmartLB;
    }

    @DataBoundSetter
    public void setDisableSmartLB(boolean disableSmartLB) {
        this.disableSmartLB = disableSmartLB;
    }

    @Extension
    public static class DescriptorImpl extends JobPropertyDescriptor {

        @Override
        public String getDisplayName() {
            return "Smart Load Balancer";
        }

        @Override
        public boolean isApplicable(Class<? extends Job> jobType) {
            return true;
        }
    }
}
