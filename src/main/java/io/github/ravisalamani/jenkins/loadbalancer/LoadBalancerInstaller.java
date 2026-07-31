package io.github.ravisalamani.jenkins.loadbalancer;

import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.LoadBalancer;
import hudson.model.Queue;
import jenkins.model.Jenkins;

import java.util.logging.Logger;

/**
 * Installs {@link SmartLoadBalancer} as the global Jenkins load balancer
 * after all extensions are registered.
 *
 * <p>The previously active balancer is captured and passed to
 * {@link SmartLoadBalancer} as its fallback, so:
 * <ul>
 *   <li>Jobs that opt out via {@link SmartLBJobProperty} are handled by the
 *       previous balancer (typically Jenkins' built-in consistent-hash).</li>
 *   <li>Any unexpected scoring exception falls back safely instead of
 *       leaving builds stuck in the queue.</li>
 *   <li>If another balancer (e.g. leastload) was already installed, it
 *       becomes the fallback rather than being silently discarded.</li>
 * </ul>
 */
public class LoadBalancerInstaller {

    private static final Logger LOGGER =
            Logger.getLogger(LoadBalancerInstaller.class.getName());

    @Initializer(after = InitMilestone.EXTENSIONS_AUGMENTED)
    public static void install() {
        Queue queue = Jenkins.get().getQueue();
        LoadBalancer existing = queue.getLoadBalancer();

        if (existing instanceof SmartLoadBalancer) {
            LOGGER.info("SmartLB: already installed, skipping re-install.");
            return;
        }

        if (existing != LoadBalancer.DEFAULT) {
            LOGGER.warning("SmartLB: another load balancer is active ("
                    + existing.getClass().getName()
                    + "). SmartLB will replace it and use it as fallback. "
                    + "Consider removing the conflicting plugin.");
        }

        queue.setLoadBalancer(new SmartLoadBalancer(existing));
        LOGGER.info("SmartLB: installed (fallback=" + existing.getClass().getSimpleName() + ").");
    }
}
