package dev.bnacar.distributedratelimiter.monitoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.Duration;

/**
 * Reports the worker's local health to Redis for cluster-wide monitoring.
 * This implements the "Heartbeat" pattern for distributed adaptive rate limiting.
 */
@Component
public class WorkerHealthReporter {
    private static final Logger logger = LoggerFactory.getLogger(WorkerHealthReporter.class);
    private static final String CPU_METRIC_PREFIX = "ratelimiter:health:cpu:";
    
    private final RedisTemplate<String, Object> redisTemplate;
    private final com.sun.management.OperatingSystemMXBean osBean;
    private final String workerId;

    @Autowired
    public WorkerHealthReporter(@Autowired(required = false) @Qualifier("rateLimiterRedisTemplate") RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.osBean = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        
        // Use HOSTNAME or a random UUID as workerId
        String hostname = System.getenv("HOSTNAME");
        this.workerId = (hostname != null && !hostname.isEmpty()) ? hostname : java.util.UUID.randomUUID().toString();
        
        logger.info("WorkerHealthReporter initialized for node: {}", this.workerId);
    }

    @Value("${ratelimiter.adaptive.enabled:true}")
    private boolean enabled;

    @Scheduled(fixedRate = 3000)
    public void reportHealth() {
        if (!enabled || redisTemplate == null) return;

        try {
            double myCpu = osBean.getProcessCpuLoad();
            // Handle sub-zero during JVM warmup
            if (myCpu < 0) myCpu = 0.0;

            redisTemplate.opsForValue().set(
                CPU_METRIC_PREFIX + workerId,
                String.valueOf(myCpu),
                Duration.ofSeconds(10) // TTL prevents dead workers from staying in the pool
            );
            
            logger.trace("Reported CPU health: {} for node: {}", myCpu, workerId);
        } catch (Exception e) {
            logger.error("Failed to report health: {}", e.getMessage());
        }
    }
}
