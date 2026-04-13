package dev.bnacar.distributedratelimiter.schedule;

import dev.bnacar.distributedratelimiter.ratelimit.ConfigurationResolver;
import dev.bnacar.distributedratelimiter.ratelimit.RateLimitAlgorithm;
import dev.bnacar.distributedratelimiter.ratelimit.RateLimitConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Service for managing scheduled rate limit configurations.
 * Evaluates schedules and applies appropriate rate limits based on time-based rules.
 */
@Service
public class ScheduleManagerService {
    
    private static final Logger logger = LoggerFactory.getLogger(ScheduleManagerService.class);
    private static final String SCHEDULES_KEY = "ratelimiter:schedules";
    
    private final Map<String, RateLimitSchedule> localSchedules = new ConcurrentHashMap<>();
    private final Map<String, RateLimitConfig> activeScheduleConfigs = new ConcurrentHashMap<>();
    private final ScheduledExecutorService transitionExecutor;
    private final org.springframework.data.redis.core.RedisTemplate<String, Object> redisTemplate;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    
    public ScheduleManagerService(@org.springframework.beans.factory.annotation.Autowired(required = false) 
                                 @org.springframework.beans.factory.annotation.Qualifier("rateLimiterRedisTemplate") 
                                 org.springframework.data.redis.core.RedisTemplate<String, Object> redisTemplate,
                                 com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.transitionExecutor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "Schedule-Transition");
            t.setDaemon(true);
            return t;
        });
    }
    
    /**
     * Evaluate schedules every minute to check for active schedules.
     */
    @Scheduled(fixedRate = 60000)
    public void evaluateSchedules() {
        syncFromRedis();
        Instant now = Instant.now();
        logger.debug("Evaluating schedules at {}", now);
        
        List<RateLimitSchedule> activeSchedules = findActiveSchedules(now);
        logger.debug("Found {} active schedules", activeSchedules.size());
        
        activeScheduleConfigs.clear();
        
        List<RateLimitSchedule> sortedSchedules = new ArrayList<>(activeSchedules);
        sortedSchedules.sort(Comparator.comparingInt(RateLimitSchedule::getPriority));
        
        for (RateLimitSchedule schedule : sortedSchedules) {
            if (schedule.getActiveLimits() != null) {
                activeScheduleConfigs.put(schedule.getKeyPattern(), schedule.getActiveLimits());
                logger.info("APPLIED active schedule '{}' for pattern '{}' (Priority: {}, Capacity: {})", 
                    schedule.getName(), schedule.getKeyPattern(), schedule.getPriority(), 
                    schedule.getActiveLimits().getCapacity());
            }
        }
    }

    private void syncFromRedis() {
        if (redisTemplate == null) return;
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(SCHEDULES_KEY);
            localSchedules.clear();
            
            // Ensure mapper handles unknown properties gracefully
            objectMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                
            entries.forEach((k, v) -> {
                try {
                    RateLimitSchedule s = objectMapper.convertValue(v, RateLimitSchedule.class);
                    localSchedules.put(s.getName(), s);
                } catch (Exception e) {
                    logger.error("Failed to deserialize schedule {}: {}", k, e.getMessage());
                }
            });
        } catch (Exception e) {
            logger.error("Failed to sync schedules from Redis: {}", e.getMessage());
        }
    }

    private void saveToRedis(RateLimitSchedule schedule) {
        if (redisTemplate == null) return;
        redisTemplate.opsForHash().put(SCHEDULES_KEY, schedule.getName(), schedule);
    }

    private void deleteFromRedis(String name) {
        if (redisTemplate == null) return;
        redisTemplate.opsForHash().delete(SCHEDULES_KEY, name);
    }
    
    /**
     * Find all currently active schedules.
     */
    public List<RateLimitSchedule> findActiveSchedules() {
        syncFromRedis();
        return findActiveSchedules(Instant.now());
    }
    
    /**
     * Find all currently active schedules at a specific time.
     */
    public List<RateLimitSchedule> findActiveSchedules(Instant time) {
        List<RateLimitSchedule> active = new ArrayList<>();
        
        for (RateLimitSchedule schedule : localSchedules.values()) {
            if (!schedule.isEnabled()) {
                continue;
            }
            
            if (isScheduleActive(schedule, time)) {
                active.add(schedule);
            }
        }
        
        active.sort(Comparator.comparingInt(RateLimitSchedule::getPriority).reversed());
        return active;
    }
    
    private boolean isScheduleActive(RateLimitSchedule schedule, Instant time) {
        switch (schedule.getType()) {
            case ONE_TIME:
            case EVENT_DRIVEN:
                Instant start = schedule.getStartTime();
                Instant end = schedule.getEndTime();
                return start != null && end != null && !time.isBefore(start) && time.isBefore(end);
            case RECURRING:
                return isRecurringScheduleActive(schedule, time);
            default:
                return false;
        }
    }
    
    private boolean isRecurringScheduleActive(RateLimitSchedule schedule, Instant time) {
        String cronExpr = schedule.getCronExpression();
        if (cronExpr == null || cronExpr.trim().isEmpty()) return false;
        
        try {
            CronExpression cron = CronExpression.parse(cronExpr);
            ZonedDateTime zonedTime = time.atZone(schedule.getTimezone() != null ? schedule.getTimezone() : java.time.ZoneId.of("UTC"));
            
            // Look back 1 minute to see if a window started
            ZonedDateTime lastExecution = cron.next(zonedTime.minusMinutes(1).minusSeconds(1));
            if (lastExecution != null) {
                long diffSeconds = java.time.Duration.between(lastExecution, zonedTime).getSeconds();
                // A schedule is "active" for 1 minute after its cron trigger time
                return diffSeconds >= 0 && diffSeconds < 60;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Get the active configuration for a specific key.
     */
    public RateLimitConfig getActiveConfig(String key) {
        if (activeScheduleConfigs.isEmpty()) return null;

        RateLimitConfig exactMatch = activeScheduleConfigs.get(key);
        if (exactMatch != null) return exactMatch;
        
        for (Map.Entry<String, RateLimitConfig> entry : activeScheduleConfigs.entrySet()) {
            if (matchesPattern(key, entry.getKey())) return entry.getValue();
        }
        return null;
    }
    
    private boolean matchesPattern(String key, String pattern) {
        if (pattern.equals("*")) return true;
        String regex = pattern.replace("*", ".*");
        return key.matches("^" + regex + "$");
    }
    
    public void createSchedule(RateLimitSchedule schedule) {
        validateSchedule(schedule);
        saveToRedis(schedule);
        evaluateSchedules();
    }
    
    public void updateSchedule(String name, RateLimitSchedule schedule) {
        schedule.setName(name);
        validateSchedule(schedule);
        saveToRedis(schedule);
        evaluateSchedules();
    }
    
    public void deleteSchedule(String name) {
        deleteFromRedis(name);
        evaluateSchedules();
    }
    
    public RateLimitSchedule getSchedule(String name) {
        syncFromRedis();
        return localSchedules.get(name);
    }
    
    public List<RateLimitSchedule> getAllSchedules() {
        syncFromRedis();
        return new ArrayList<>(localSchedules.values());
    }
    
    public void activateSchedule(String name) {
        RateLimitSchedule s = getSchedule(name);
        if (s != null) { s.setEnabled(true); saveToRedis(s); evaluateSchedules(); }
    }
    
    public void deactivateSchedule(String name) {
        RateLimitSchedule s = getSchedule(name);
        if (s != null) { s.setEnabled(false); saveToRedis(s); evaluateSchedules(); }
    }
    
    private void validateSchedule(RateLimitSchedule schedule) {
        if (schedule.getName() == null || schedule.getKeyPattern() == null || schedule.getActiveLimits() == null) {
            throw new IllegalArgumentException("Missing required schedule fields");
        }
    }
    
    public static RateLimitConfig createRateLimitConfig(Integer capacity, Integer refillRate, String algorithm) {
        RateLimitAlgorithm algo = algorithm != null ? RateLimitAlgorithm.valueOf(algorithm.toUpperCase()) : RateLimitAlgorithm.TOKEN_BUCKET;
        return new RateLimitConfig(capacity, refillRate, 60000, algo);
    }
    
    @PreDestroy
    public void shutdown() {
        transitionExecutor.shutdown();
    }
}
