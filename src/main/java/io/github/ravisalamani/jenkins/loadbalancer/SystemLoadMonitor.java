package io.github.ravisalamani.jenkins.loadbalancer;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import hudson.remoting.Callable;
import jenkins.model.Jenkins;
import org.jenkinsci.remoting.RoleChecker;

import java.io.IOException;
import java.io.Serializable;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Periodically polls the 1-minute system load average from every online node
 * via the remoting channel and caches the result in memory.
 *
 * <p>{@link SmartLoadBalancer} reads this cache synchronously during scheduling
 * so there is no per-schedule I/O.
 *
 * <p>On Windows agents, {@code getSystemLoadAverage()} returns -1 (unsupported).
 * We treat -1 as 0.0 so Windows nodes are not penalised.
 */
@Extension
public class SystemLoadMonitor extends AsyncPeriodicWork {

    private static final Logger LOGGER =
            Logger.getLogger(SystemLoadMonitor.class.getName());

    /** Poll every 30 seconds. */
    private static final long PERIOD_MS = 30_000L;

    /** computerName → most recent 1-min load average (0.0 if unknown/Windows). */
    private static final ConcurrentHashMap<String, Double> CACHE =
            new ConcurrentHashMap<>();

    public SystemLoadMonitor() {
        super("SmartLB-SystemLoadMonitor");
    }

    @Override
    public long getRecurrencePeriod() {
        return PERIOD_MS;
    }

    @Override
    protected void execute(TaskListener listener) throws IOException, InterruptedException {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) return;

        for (hudson.model.Computer computer : jenkins.getComputers()) {
            if (!computer.isOnline() || computer.getChannel() == null) continue;
            try {
                Double load = computer.getChannel().call(new GetSystemLoad());
                if (load != null) {
                    CACHE.put(computer.getName(), load);
                }
            } catch (Exception e) {
                LOGGER.log(Level.FINE,
                        "SmartLB: could not poll load from " + computer.getName(), e);
            }
        }
    }

    /**
     * Returns the cached load average for the named computer.
     * Falls back to 0.0 if no measurement has been taken yet or polling failed.
     */
    public static double getLoad(String computerName) {
        return CACHE.getOrDefault(computerName, 0.0);
    }

    // -------------------------------------------------------------------------
    // Remote callable — executed on the agent JVM
    // -------------------------------------------------------------------------

    private static final class GetSystemLoad
            implements Callable<Double, Exception>, Serializable {

        private static final long serialVersionUID = 1L;

        @Override
        public Double call() {
            OperatingSystemMXBean os =
                    ManagementFactory.getOperatingSystemMXBean();
            double load = os.getSystemLoadAverage();
            return load < 0 ? 0.0 : load;
        }

        @Override
        public void checkRoles(RoleChecker checker) throws SecurityException {
            // Read-only JMX call; no sensitive roles required
        }
    }
}
