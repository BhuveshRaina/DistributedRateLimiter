package dev.bnacar.distributedratelimiter.adaptive;

import dev.bnacar.distributedratelimiter.monitoring.MetricsService;
import dev.bnacar.distributedratelimiter.models.MetricsResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Robust System Metrics Collector.
 * Measures 'End-to-End API Latency' using filtered Micrometer timers.
 */
@Component
public class SystemMetricsCollector {
    
    private static final Logger logger = LoggerFactory.getLogger(SystemMetricsCollector.class);
    
    private final MeterRegistry meterRegistry;
    private final HealthContributorRegistry healthRegistry;
    private final MetricsService metricsService;
    private final OperatingSystemMXBean osBean;
    private final MemoryMXBean memoryBean;
    
    // Internal counters for delta calculation
    private final AtomicLong lastLocalRequests = new AtomicLong(0);
    private final AtomicLong lastLocalProcessingTime = new AtomicLong(0); // Store double as long bits
    private final AtomicLong lastLocalDenied = new AtomicLong(0);
    private final AtomicLong lastGlobalRequests = new AtomicLong(0);
    private final AtomicLong lastGlobalProcessingTime = new AtomicLong(0); // Store double as long bits
    private final AtomicLong lastGlobalDenied = new AtomicLong(0);

    // The current thread-safe snapshot
    private final AtomicReference<SystemHealth> currentSnapshot = new AtomicReference<>();
    private final AtomicReference<SystemHealth> mockOverride = new AtomicReference<>(null);
    private boolean initialized = false;

    public void setMockHealth(SystemHealth health) {
        this.mockOverride.set(health);
    }

    public void clearMockHealth() {
        this.mockOverride.set(null);
    }
    
    public SystemMetricsCollector(MeterRegistry meterRegistry, 
                                 HealthContributorRegistry healthRegistry,
                                 MetricsService metricsService) {
        this.meterRegistry = meterRegistry;
        this.healthRegistry = healthRegistry;
        this.metricsService = metricsService;
        this.osBean = ManagementFactory.getOperatingSystemMXBean();
        this.memoryBean = ManagementFactory.getMemoryMXBean();
        this.currentSnapshot.set(SystemHealth.builder().build());
        
        // Initialize double bits to 0.0
        this.lastLocalProcessingTime.set(Double.doubleToRawLongBits(0.0));
        this.lastGlobalProcessingTime.set(Double.doubleToRawLongBits(0.0));
    }

    public void refresh() {
        // 0. Mock Override (For Testing)
        SystemHealth mock = mockOverride.get();
        if (mock != null) {
            currentSnapshot.set(mock);
            return;
        }

        // 1. Local Deltas (Surgical filtering for End-to-End Rate Limiter Latency)
        long localTotal = getLocalTotalRequestsRaw();
        double localTime = getLocalTotalTimeRaw();
        long localDenied = getLocalTotalDeniedRaw();
        
        long deltaLocalTotal = localTotal - lastLocalRequests.getAndSet(localTotal);
        double prevLocalTime = Double.longBitsToDouble(lastLocalProcessingTime.getAndSet(Double.doubleToRawLongBits(localTime)));
        double deltaLocalTime = localTime - prevLocalTime;
        long deltaLocalDenied = localDenied - lastLocalDenied.getAndSet(localDenied);

        // 2. Global Deltas (For distributed error sensing)
        MetricsResponse globalMetrics = metricsService.getMetrics();
        long globalTotal = globalMetrics.getTotalAllowedRequests() + globalMetrics.getTotalDeniedRequests();
        double globalTime = globalMetrics.getTotalProcessingTimeMs();
        long globalFailures = globalMetrics.getTotalFailures();

        long deltaGlobalTotal = globalTotal - lastGlobalRequests.getAndSet(globalTotal);
        double prevGlobalTime = Double.longBitsToDouble(lastGlobalProcessingTime.getAndSet(Double.doubleToRawLongBits(globalTime)));
        double deltaGlobalTime = globalTime - prevGlobalTime;
        long deltaGlobalFailures = globalFailures - lastGlobalDenied.getAndSet(globalFailures);

        // SAFETY WARMUP
        if (!initialized) {
            initialized = true;
            currentSnapshot.set(SystemHealth.builder()
                .cpuUtilization(getCPUUtilizationRaw())
                .memoryUtilization(getMemoryUtilizationRaw())
                .responseTimeP95(0.0)
                .errorRate(0.0)
                .redisHealthy(isRedisHealthy())
                .downstreamServicesHealthy(true)
                .build());
            return;
        }

        // 3. Signal Calculation
        double p95 = 0.0;
        double errorRate = 0.0;

        // Use Global Metrics for high-precision Latency if local timers are empty
        // This ensures we see the Redis processing time even if Spring HTTP timers are being buffered
        if (deltaLocalTotal > 0 && deltaLocalTime > 0) {
            p95 = deltaLocalTime / (double) deltaLocalTotal;
        } else if (deltaGlobalTotal > 0) {
            p95 = deltaGlobalTime / (double) deltaGlobalTotal;
        }
        
        // Use Global for Failures (5xx across the cluster)
        if (deltaGlobalTotal > 0) {
            errorRate = (double) deltaGlobalFailures / deltaGlobalTotal;
        }

        SystemHealth newHealth = SystemHealth.builder()
            .cpuUtilization(getCPUUtilizationRaw())
            .memoryUtilization(getMemoryUtilizationRaw())
            .responseTimeP95(p95)
            .errorRate(errorRate)
            .redisHealthy(isRedisHealthy())
            .downstreamServicesHealthy(true)
            .build();
            
        currentSnapshot.set(newHealth);
    }
    
    public SystemHealth getCurrentHealth() {
        return currentSnapshot.get();
    }
    
    private double getCPUUtilizationRaw() {
        double procCpu = 0.0;

        if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
            com.sun.management.OperatingSystemMXBean sunOsBean = (com.sun.management.OperatingSystemMXBean) osBean;
            // Actual CPU usage of ONLY this Java process (0.0 to 1.0)
            procCpu = sunOsBean.getProcessCpuLoad();
        }

        // Fallback to 0 if negative (common during JVM warmup)
        return Math.max(0.0, procCpu);
    }
    
    private double getMemoryUtilizationRaw() {
        long maxMemory = memoryBean.getHeapMemoryUsage().getMax();
        long usedMemory = memoryBean.getHeapMemoryUsage().getUsed();
        return (maxMemory > 0) ? (double) usedMemory / maxMemory : 0.0;
    }
    
    private long getLocalTotalRequestsRaw() {
        try {
            return meterRegistry.find("http.server.requests")
                .tag("uri", (val) -> val.startsWith("/api/ratelimit"))
                .timers().stream().mapToLong(Timer::count).sum();
        } catch (Exception e) { return 0; }
    }

    private double getLocalTotalTimeRaw() {
        try {
            return meterRegistry.find("http.server.requests")
                .tag("uri", (val) -> val.startsWith("/api/ratelimit"))
                .timers().stream().mapToDouble(t -> t.totalTime(TimeUnit.MILLISECONDS)).sum();
        } catch (Exception e) { return 0.0; }
    }

    private long getLocalTotalDeniedRaw() {
        try {
            return (long) meterRegistry.find("http.server.requests")
                .tag("status", (val) -> val.startsWith("5"))
                .timers().stream().mapToLong(Timer::count).sum();
        } catch (Exception e) { return 0; }
    }
    
    private boolean isRedisHealthy() {
        try {
            Object contributor = healthRegistry.getContributor("redis");
            if (contributor instanceof HealthIndicator) {
                HealthIndicator redisHealth = (HealthIndicator) contributor;
                return redisHealth.health().getStatus().equals(org.springframework.boot.actuate.health.Status.UP);
            }
        } catch (Exception e) {}
        return true;
    }
}
