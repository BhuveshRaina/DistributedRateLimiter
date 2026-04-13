package dev.bnacar.distributedratelimiter.adaptive;

import dev.bnacar.distributedratelimiter.ratelimit.ConfigurationResolver;
import dev.bnacar.distributedratelimiter.ratelimit.RateLimitConfig;
import dev.bnacar.distributedratelimiter.ratelimit.RateLimitAlgorithm;
import dev.bnacar.distributedratelimiter.ratelimit.RateLimiterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * DISTRIBUTED Adaptive Rate Limiting Engine.
 * Highly optimized with Pipelined Redis operations for enterprise-scale.
 */
@Service
public class AdaptiveRateLimitEngine {
    
    private static final Logger logger = LoggerFactory.getLogger(AdaptiveRateLimitEngine.class);
    private static final String ADAPTED_LIMITS_KEY = "ratelimiter:adaptive:limits";
    private static final String TRACKED_KEYS_SET = "ratelimiter:adaptive:tracked";
    private static final String ADAPTIVE_TARGETS_KEY = "ratelimiter:adaptive:targets";
    
    private final SystemMetricsCollector metricsCollector;
    private final UserMetricsModeler userMetricsModeler;
    private final AimdPolicyEngine policyEngine;
    private final ConfigurationResolver configurationResolver;
    private final RateLimiterService rateLimiterService;
    private final RedisTemplate<String, Object> redisTemplate;
    
    private final boolean enabled;
    private final double minConfidenceThreshold;
    private final int minCapacity;
    private final int maxCapacity;
    private final double maxAdjustmentFactor;
    
    @Autowired
    public AdaptiveRateLimitEngine(
            SystemMetricsCollector metricsCollector,
            UserMetricsModeler userMetricsModeler,
            AimdPolicyEngine policyEngine,
            @Lazy ConfigurationResolver configurationResolver,
            @Lazy RateLimiterService rateLimiterService,
            @Autowired(required = false) @Qualifier("rateLimiterRedisTemplate") RedisTemplate<String, Object> redisTemplate,
            @Value("${ratelimiter.adaptive.enabled:true}") boolean enabled,
            @Value("${ratelimiter.adaptive.min-confidence-threshold:0.7}") double minConfidenceThreshold,
            @Value("${ratelimiter.adaptive.max-adjustment-factor:2.0}") double maxAdjustmentFactor,
            @Value("${ratelimiter.adaptive.min-capacity:10}") int minCapacity,
            @Value("${ratelimiter.adaptive.max-capacity:100000}") int maxCapacity) {
        
        this.metricsCollector = metricsCollector;
        this.userMetricsModeler = userMetricsModeler;
        this.policyEngine = policyEngine;
        this.configurationResolver = configurationResolver;
        this.rateLimiterService = rateLimiterService;
        this.redisTemplate = redisTemplate;
        this.enabled = enabled;
        this.minConfidenceThreshold = minConfidenceThreshold;
        this.maxAdjustmentFactor = maxAdjustmentFactor;
        this.minCapacity = minCapacity;
        this.maxCapacity = maxCapacity;
    }

    @Scheduled(fixedRateString = "${ratelimiter.adaptive.evaluation-interval-ms:5000}")
    public void evaluateAdaptations() {
        if (!enabled || redisTemplate == null) return;
        syncWithConfiguration();
        
        // REFRESH HEALTH SNAPSHOT ONCE PER CYCLE
        metricsCollector.refresh();
        
        Set<String> allConfigKeys = configurationResolver.getValidConfigKeys();
        Set<String> specificKeys = configurationResolver.getSpecificKeysOnly();
        
        // --- PHASE 1: COLLECT EVERYTHING IN ONE NETWORK CALL (Zero N+1) ---
        // Use allConfigKeys for the global stats to ensure patterns contribute to the mean RPS
        Map<String, UserMetrics> allMetrics = userMetricsModeler.fetchAndCalculateAllMetrics(allConfigKeys);

        // ALWAYS LOG TELEMETRY once per cycle
        SystemHealth health = metricsCollector.getCurrentHealth();
        logger.info("[TELEMETRY] CPU: {}% | Latency: {}ms | Errors: {}%", 
            String.format("%.2f", health.getCpuUtilization() * 100), 
            String.format("%.2f", health.getResponseTimeP95()), 
            Math.round(health.getErrorRate() * 100));

        // --- PHASE 2: EVALUATE INDIVIDUAL KEYS (Entirely In-Memory!) ---
        // Filter out zero RPS users for population count (Edge Case F)
        int activeUserCount = (int) allMetrics.values().stream()
            .filter(m -> m.getCurrentRequestRate() > 0)
            .count();

        logger.debug("[ADAPTIVE ENGINE] Evaluating {} configured keys. Active traffic population: {}", specificKeys.size(), activeUserCount);

        for (String key : specificKeys) {
            RateLimitConfig config = configurationResolver.getBaseConfig(key);
            if (config != null && config.isAdaptiveEnabled()) {
                
                // Get pre-calculated metrics
                UserMetrics userMetrics = allMetrics.getOrDefault(key, 
                    UserMetrics.builder().currentRequestRate(0).denialRate(0).zScore(0.0).build());

                evaluateKey(key, userMetrics, activeUserCount);
            }
        }
    }

    private void evaluateKey(String key, UserMetrics userMetrics, int activeUserCount) { 
        AdaptedLimits current = getAdaptedLimits(key); 
        if (current != null && "Manual override".equals(current.reasoning.get("decision"))) return;
        
        try {
            AdaptationDecision decision = generateAdaptationDecision(key, userMetrics, current, activeUserCount);
            
            // IDLE PROTECTION LOGIC:
            if (userMetrics.getCurrentRequestRate() <= 0) {
                SystemHealth health = metricsCollector.getCurrentHealth();
                double uCurr = Math.max(health.getCpuUtilization(), Math.max(health.getResponseTimeP95()/2000.0, health.getErrorRate()/0.20));
                
                // If the system is stressed (>70%) but THIS user is idle, protect them from MD.
                if (uCurr > 0.70 && decision.getRecommendedCapacity() < ((current != null) ? current.adaptedCapacity : configurationResolver.getBaseConfig(key).getCapacity())) {
                    logger.debug("[PROTECTED] Key: {} is idle during stress. Skipping decrease.", key);
                    return;
                }
            }

            if (decision.shouldAdapt() && decision.getConfidence() >= minConfidenceThreshold) {
                applyAdaptation(key, decision, current);
            } else if (current != null) {
                // Even if we don't change the numbers, update the reasoning so logs/UI aren't stale
                current.reasoning = decision.getReasoning();
                current.timestamp = Instant.now();
                redisTemplate.opsForHash().put(ADAPTED_LIMITS_KEY, key, current);
            }
        } catch (Exception e) {
            logger.error("Error evaluating key {}: {}", key, e.getMessage());
        }
    }

    private AdaptationDecision generateAdaptationDecision(String key, UserMetrics userMetrics, AdaptedLimits current, int activeUserCount) {
        RateLimitConfig config = configurationResolver.getBaseConfig(key);
        int lBase = config.getCapacity();
        int rBase = config.getRefillRate();
        
        int lOld = (current != null) ? current.adaptedCapacity : lBase;
        int rOld = (current != null) ? current.adaptedRefillRate : rBase;

        return policyEngine.determineAdaptation(metricsCollector.getCurrentHealth(), 
                                              userMetrics, 
                                              lOld, rOld, lBase, rBase, activeUserCount);
    }
    
    private void applyAdaptation(String key, AdaptationDecision decision, AdaptedLimits current) {
        RateLimitConfig original = configurationResolver.getBaseConfig(key);
        int newCap = decision.getRecommendedCapacity();
        int newRefill = decision.getRecommendedRefillRate();
        
        // For window-based algorithms, capacity and refill (limit) are synonymous
        if (original.getAlgorithm() == RateLimitAlgorithm.SLIDING_WINDOW || 
            original.getAlgorithm() == RateLimitAlgorithm.FIXED_WINDOW) {
            newRefill = newCap;
        }
        
        // ONLY UPDATE IF VALUES CHANGED
        int prevCap = (current != null) ? current.adaptedCapacity : original.getCapacity();
        int prevRefill = (current != null) ? current.adaptedRefillRate : original.getRefillRate();
        
        if (newCap != prevCap || newRefill != prevRefill) {
            AdaptedLimits adapted = new AdaptedLimits(
                original.getCapacity(), original.getRefillRate(),
                newCap, newRefill, Instant.now(), decision.getReasoning(), decision.getConfidence()
            );
            
            redisTemplate.opsForHash().put(ADAPTED_LIMITS_KEY, key, adapted);
            logger.info("[REDIS UPDATE] Key: {} | Transition: {}/{} -> {}/{} (Algorithm: {})", 
                key, prevCap, prevRefill, newCap, newRefill, original.getAlgorithm());
        }
    }

    private int enforceConstraints(int recommended, int original) {
        int constrained = Math.max(minCapacity, Math.min(maxCapacity, recommended));
        int maxAllowed = (int) (original * maxAdjustmentFactor);
        int minAllowed = (int) (original / maxAdjustmentFactor);
        return Math.max(minAllowed, Math.min(maxAllowed, constrained));
    }

    public AdaptedLimits getAdaptedLimits(String key) {
        if (redisTemplate == null) return null;
        Object val = redisTemplate.opsForHash().get(ADAPTED_LIMITS_KEY, key); if (val == null) return null; if (val instanceof AdaptedLimits) return (AdaptedLimits) val; return new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()).convertValue(val, AdaptedLimits.class);
    }
    
    private final java.util.concurrent.atomic.AtomicLong eventCounter = new java.util.concurrent.atomic.AtomicLong(0);

    public void recordTrafficEvent(String key, int tokens, boolean allowed) {
        if (redisTemplate == null) return; // Remove 'enabled' check
        
        redisTemplate.opsForSet().add(TRACKED_KEYS_SET, key);
        userMetricsModeler.recordRequest(key, tokens, allowed);
    }

    public AdaptiveStatusInfo getStatus(String key) {
        AdaptedLimits adapted = getAdaptedLimits(key);
        RateLimitConfig config = configurationResolver.getBaseConfig(key);
        boolean isAdaptiveEnabled = config != null && config.isAdaptiveEnabled();
        
        if (!isAdaptiveEnabled) {
            return new AdaptiveStatusInfo("DISABLED", 0.0, config != null ? config.getCapacity() : 10, 
                                        config != null ? config.getRefillRate() : 2, 
                                        config != null ? config.getCapacity() : 10, 
                                        config != null ? config.getRefillRate() : 2, 
                                        new HashMap<>(), false);
        }
        
        if (adapted == null) {
            return new AdaptiveStatusInfo("ADAPTIVE", 0.0, config != null ? config.getCapacity() : 10, 
                                        config != null ? config.getRefillRate() : 2, 
                                        config != null ? config.getCapacity() : 10, 
                                        config != null ? config.getRefillRate() : 2, 
                                        new HashMap<>(), true);
        }
        
        return new AdaptiveStatusInfo("ADAPTIVE", adapted.confidence, adapted.originalCapacity, 
                                    adapted.originalRefillRate, adapted.adaptedCapacity, 
                                    adapted.adaptedRefillRate, adapted.reasoning, true);
    }

    public Map<String, AdaptiveStatusInfo> getAllStatuses() {
        if (redisTemplate == null) return new HashMap<>();
        
        // Combine configured keys and dynamically tracked keys
        Set<String> allKeys = new java.util.HashSet<>(configurationResolver.getValidConfigKeys());
        
        Set<Object> trackedKeys = redisTemplate.opsForSet().members(TRACKED_KEYS_SET);
        if (trackedKeys != null) {
            for (Object k : trackedKeys) allKeys.add(k.toString());
        }
        
        Map<String, AdaptiveStatusInfo> results = new HashMap<>();
        for (String key : allKeys) {
            results.put(key, getStatus(key));
        }
        
        return results;
    }

    public void setOverride(String key, AdaptationOverride override) {
        RateLimitConfig original = configurationResolver.getBaseConfig(key);
        AdaptedLimits adapted = new AdaptedLimits(
            original != null ? original.getCapacity() : 10, 
            original != null ? original.getRefillRate() : 2,
            override.capacity, override.refillRate, Instant.now(), 
            Map.of("decision", "Manual override"), 1.0
        );
        redisTemplate.opsForHash().put(ADAPTED_LIMITS_KEY, key, adapted);
    }

    public void removeOverride(String key) {
        redisTemplate.opsForHash().delete(ADAPTED_LIMITS_KEY, key);
    }

    public void removeAdaptiveStatus(String key) {
        if (redisTemplate == null) return;
        redisTemplate.opsForHash().delete(ADAPTED_LIMITS_KEY, key);
        redisTemplate.opsForHash().delete(ADAPTIVE_TARGETS_KEY, key);
        redisTemplate.opsForSet().remove(TRACKED_KEYS_SET, key);
    }

    public void addAdaptiveTarget(String target, boolean isPattern, Integer capacity, Integer refillRate) {
        if (redisTemplate == null) return;
        AdaptiveTarget targetObj = new AdaptiveTarget(target, isPattern);
        redisTemplate.opsForHash().put(ADAPTIVE_TARGETS_KEY, target, targetObj);
        
        RateLimitConfig current = configurationResolver.getBaseConfig(target);
        RateLimitConfig updated = new RateLimitConfig(
            capacity != null ? capacity : (current != null ? current.getCapacity() : 10),
            refillRate != null ? refillRate : (current != null ? current.getRefillRate() : 2),
            current != null ? current.getCleanupIntervalMs() : 60000,
            current != null ? current.getAlgorithm() : RateLimitAlgorithm.TOKEN_BUCKET,
            true // Enable adaptive
        );

        if (isPattern) {
            configurationResolver.updatePatternConfig(target, updated);
        } else {
            configurationResolver.updateKeyConfig(target, updated);
        }
        
        // Ensure we clear any previous adaptations to start from the original values
        removeOverride(target);
    }

    public void removeAdaptiveTarget(String target) {
        if (redisTemplate == null) return;
        
        RateLimitConfig current = configurationResolver.getBaseConfig(target);
        if (current != null) {
            RateLimitConfig updated = new RateLimitConfig(
                current.getCapacity(),
                current.getRefillRate(),
                current.getCleanupIntervalMs(),
                current.getAlgorithm(),
                false // Disable adaptive
            );
            
            configurationResolver.updateKeyConfig(target, updated);
            configurationResolver.updatePatternConfig(target, updated);
        }
        
        redisTemplate.opsForHash().delete(ADAPTIVE_TARGETS_KEY, target);
        redisTemplate.opsForHash().delete(ADAPTED_LIMITS_KEY, target);
    }

    public Map<String, AdaptiveTarget> getAdaptiveTargets() {
        if (redisTemplate == null) return new HashMap<>();
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(ADAPTIVE_TARGETS_KEY);
        return entries.entrySet().stream().collect(Collectors.toMap(
            e -> e.getKey().toString(),
            e -> { Object val = e.getValue(); if (val instanceof AdaptiveTarget) return (AdaptiveTarget) val; return new com.fasterxml.jackson.databind.ObjectMapper().convertValue(val, AdaptiveTarget.class); }
        ));
    }

    // --- INNER CLASSES ---

    public static class AdaptiveStatusInfo {
        public final String mode;
        public final double confidence;
        public final int originalCapacity;
        public final int originalRefillRate;
        public final int currentCapacity;
        public final int currentRefillRate;
        public final Map<String, String> reasoning;
        private final boolean adaptiveEnabled;

        public AdaptiveStatusInfo(String m, double c, int oc, int or, int cc, int cr, Map<String, String> r, boolean ae) {
            this.mode = m; this.confidence = c; this.originalCapacity = oc; 
            this.originalRefillRate = or; this.currentCapacity = cc; this.currentRefillRate = cr; 
            this.reasoning = r; this.adaptiveEnabled = ae;
        }

        public boolean isAdaptiveEnabled() {
            return adaptiveEnabled;
        }
    }
    
    public static class AdaptedLimits implements java.io.Serializable {
        public int originalCapacity;
        public int originalRefillRate;
        public int adaptedCapacity;
        public int adaptedRefillRate;
        public java.time.Instant timestamp;
        public java.util.Map<String, String> reasoning;
        public double confidence;
        public AdaptedLimits() {}
        public AdaptedLimits(int oc, int or, int ac, int ar, java.time.Instant t, java.util.Map<String, String> r, double c) {
            this.originalCapacity = oc; this.originalRefillRate = or; this.adaptedCapacity = ac; 
            this.adaptedRefillRate = ar; this.timestamp = t; this.reasoning = r; this.confidence = c;
        }
    }

    public static class AdaptationOverride {
        public int capacity;
        public int refillRate;
        public String reason;
        public AdaptationOverride() {}
        public AdaptationOverride(int c, int r, String re) {
            this.capacity = c; this.refillRate = r; this.reason = re;
        }
    }

    public static class AdaptiveTarget implements java.io.Serializable {
        public String target;
        public boolean isPattern;
        public AdaptiveTarget() {}
        public AdaptiveTarget(String target, boolean isPattern) {
            this.target = target;
            this.isPattern = isPattern;
        }
        public String getTarget() { return target; }
        public boolean isPattern() { return isPattern; }
    }
    
    private void syncWithConfiguration() {
        try {
            Set<String> validKeys = configurationResolver.getValidConfigKeys();
            
            // SAFETY: If we can't load any keys (likely a Redis blip), don't delete anything!
            if (validKeys == null || validKeys.isEmpty()) {
                return;
            }

            Map<Object, Object> adaptedEntries = redisTemplate.opsForHash().entries(ADAPTED_LIMITS_KEY);
            Map<Object, Object> targetEntries = redisTemplate.opsForHash().entries(ADAPTIVE_TARGETS_KEY);
            
            for (Object keyObj : adaptedEntries.keySet()) {
                String key = keyObj.toString();
                
                // Only delete if the key is definitely gone from both configs and patterns
                if (!validKeys.contains(key) && !key.startsWith("benchmark")) {
                    redisTemplate.opsForHash().delete(ADAPTED_LIMITS_KEY, key);
                    redisTemplate.opsForSet().remove(TRACKED_KEYS_SET, key);
                    continue;
                }

                // Also delete if adaptive mode was explicitly turned off for this key
                RateLimitConfig config = configurationResolver.getBaseConfig(key);
                if (config != null && !config.isAdaptiveEnabled()) {
                    redisTemplate.opsForHash().delete(ADAPTED_LIMITS_KEY, key);
                    redisTemplate.opsForSet().remove(TRACKED_KEYS_SET, key);
                }
            }
            
            for (Object keyObj : targetEntries.keySet()) {
                String key = keyObj.toString();
                if (!validKeys.contains(key)) {
                    redisTemplate.opsForHash().delete(ADAPTIVE_TARGETS_KEY, key);
                }
            }
        } catch (Exception e) {
            logger.debug("Sync cleanup failed: {}", e.getMessage());
        }
    }
}
