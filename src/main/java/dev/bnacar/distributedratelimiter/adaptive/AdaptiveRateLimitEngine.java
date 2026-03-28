package dev.bnacar.distributedratelimiter.adaptive;

import dev.bnacar.distributedratelimiter.ratelimit.ConfigurationResolver;
import dev.bnacar.distributedratelimiter.ratelimit.RateLimitConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simplified adaptive rate limiting engine focusing on System Health and User Metrics.
 */
@Service
public class AdaptiveRateLimitEngine {
    
    private static final Logger logger = LoggerFactory.getLogger(AdaptiveRateLimitEngine.class);
    
    private final SystemMetricsCollector metricsCollector;
    private final UserMetricsModeler userMetricsModeler;
    private final AdaptiveMLModel adaptiveModel;
    private final ConfigurationResolver configurationResolver;
    
    // Store adaptive configurations and overrides
    private final Map<String, AdaptedLimits> adaptedLimits = new ConcurrentHashMap<>();
    private final Map<String, AdaptationOverride> manualOverrides = new ConcurrentHashMap<>();
    
    // Track active keys that need evaluation
    private final Set<String> trackedKeys = ConcurrentHashMap.newKeySet();
    
    // Configuration
    private final boolean enabled;
    private final long evaluationIntervalMs;
    private final double minConfidenceThreshold;
    private final double maxAdjustmentFactor;
    private final int minCapacity;
    private final int maxCapacity;
    
    public AdaptiveRateLimitEngine(
            SystemMetricsCollector metricsCollector,
            UserMetricsModeler userMetricsModeler,
            AdaptiveMLModel adaptiveModel,
            @Lazy ConfigurationResolver configurationResolver,
            @Value("${ratelimiter.adaptive.enabled:true}") boolean enabled,
            @Value("${ratelimiter.adaptive.evaluation-interval-ms:300000}") long evaluationIntervalMs,
            @Value("${ratelimiter.adaptive.min-confidence-threshold:0.7}") double minConfidenceThreshold,
            @Value("${ratelimiter.adaptive.max-adjustment-factor:2.0}") double maxAdjustmentFactor,
            @Value("${ratelimiter.adaptive.min-capacity:10}") int minCapacity,
            @Value("${ratelimiter.adaptive.max-capacity:100000}") int maxCapacity) {
        
        this.metricsCollector = metricsCollector;
        this.userMetricsModeler = userMetricsModeler;
        this.adaptiveModel = adaptiveModel;
        this.configurationResolver = configurationResolver;
        this.enabled = enabled;
        this.evaluationIntervalMs = evaluationIntervalMs;
        this.minConfidenceThreshold = minConfidenceThreshold;
        this.maxAdjustmentFactor = maxAdjustmentFactor;
        this.minCapacity = minCapacity;
        this.maxCapacity = maxCapacity;
        
        logger.info("Simplified Adaptive Rate Limit Engine initialized - enabled: {}, interval: {}ms", 
                   enabled, evaluationIntervalMs);
    }
    
    /**
     * Evaluate adaptations periodically.
     */
    @Scheduled(fixedRateString = "${ratelimiter.adaptive.evaluation-interval-ms:300000}")
    public void evaluateAdaptations() {
        if (!enabled) {
            return;
        }
        
        Set<String> activeKeys = getActiveKeys();
        if (activeKeys.isEmpty()) {
            return;
        }

        logger.debug("Starting adaptive evaluation cycle for {} keys", activeKeys.size());
        
        int adaptedCount = 0;
        for (String key : activeKeys) {
            try {
                AdaptationDecision decision = generateAdaptationDecision(key);
                
                if (decision.shouldAdapt() && decision.getConfidence() >= minConfidenceThreshold) {
                    applyAdaptation(key, decision);
                    adaptedCount++;
                }
            } catch (Exception e) {
                logger.error("Error evaluating adaptation for key: {}", key, e);
            }
        }
        
        if (adaptedCount > 0) {
            logger.info("Adaptive evaluation completed - adapted {} keys", adaptedCount);
        }
    }
    
    /**
     * Generate adaptation decision for a key using System Health and User Metrics.
     */
    private AdaptationDecision generateAdaptationDecision(String key) {
        // Check for manual override
        if (manualOverrides.containsKey(key)) {
            logger.debug("Manual override active for key: {}", key);
            return AdaptationDecision.builder()
                .shouldAdapt(false)
                .confidence(1.0)
                .reasoning(Map.of("decision", "Manual override active"))
                .build();
        }
        
        // Get current configuration
        RateLimitConfig currentConfig = configurationResolver.getBaseConfig(key);
        
        // Collect signals
        SystemHealth health = metricsCollector.getCurrentHealth();
        UserMetrics userMetrics = userMetricsModeler.getUserMetrics(key);
        
        // Use ML model to generate decision
        return adaptiveModel.predict(health, userMetrics, 
                                     currentConfig.getCapacity(), 
                                     currentConfig.getRefillRate());
    }
    
    /**
     * Apply adaptation decision.
     */
    private void applyAdaptation(String key, AdaptationDecision decision) {
        RateLimitConfig originalConfig = configurationResolver.getBaseConfig(key);
        
        // Apply safety constraints
        int newCapacity = enforceConstraints(decision.getRecommendedCapacity(), 
                                            originalConfig.getCapacity());
        int newRefillRate = enforceConstraints(decision.getRecommendedRefillRate(), 
                                              originalConfig.getRefillRate());
        
        // Store adapted limits
        AdaptedLimits adapted = new AdaptedLimits(
            originalConfig.getCapacity(),
            originalConfig.getRefillRate(),
            newCapacity,
            newRefillRate,
            Instant.now(),
            decision.getReasoning(),
            decision.getConfidence()
        );
        
        adaptedLimits.put(key, adapted);
        
        logger.info("Applied adaptation for key {}: capacity {} -> {}, refillRate {} -> {}, confidence: {}", 
                   key, 
                   originalConfig.getCapacity(), newCapacity,
                   originalConfig.getRefillRate(), newRefillRate,
                   decision.getConfidence());
    }
    
    /**
     * Enforce safety constraints on adapted values.
     */
    private int enforceConstraints(int recommendedValue, int originalValue) {
        int constrained = Math.max(minCapacity, Math.min(maxCapacity, recommendedValue));
        
        int maxAllowed = (int) (originalValue * maxAdjustmentFactor);
        int minAllowed = (int) (originalValue / maxAdjustmentFactor);
        
        constrained = Math.max(minAllowed, Math.min(maxAllowed, constrained));
        
        return constrained;
    }
    
    /**
     * Get active keys for evaluation.
     */
    private Set<String> getActiveKeys() {
        Set<String> keys = new java.util.HashSet<>();
        keys.addAll(trackedKeys);
        keys.addAll(adaptedLimits.keySet());
        keys.addAll(manualOverrides.keySet());
        return keys;
    }
    
    /**
     * Get adaptive status for a key.
     */
    public AdaptiveStatusInfo getStatus(String key) {
        AdaptationOverride override = manualOverrides.get(key);
        if (override != null) {
            RateLimitConfig originalConfig = configurationResolver.getBaseConfig(key);
            Map<String, String> reasoning = new HashMap<>();
            reasoning.put("decision", "Manual override active");
            reasoning.put("reason", override.reason);
            
            return new AdaptiveStatusInfo(
                "OVERRIDE",
                1.0,
                originalConfig.getCapacity(),
                originalConfig.getRefillRate(),
                override.capacity,
                override.refillRate,
                reasoning
            );
        }

        AdaptedLimits adapted = adaptedLimits.get(key);
        if (adapted == null) {
            RateLimitConfig config = configurationResolver.getBaseConfig(key);
            
            if (trackedKeys.contains(key)) {
                Map<String, String> reasoning = new HashMap<>();
                reasoning.put("decision", "Collecting metrics");
                reasoning.put("status", "Learning in progress");
                
                return new AdaptiveStatusInfo(
                    "LEARNING",
                    0.3,
                    config.getCapacity(),
                    config.getRefillRate(),
                    config.getCapacity(),
                    config.getRefillRate(),
                    reasoning
                );
            }
            
            return new AdaptiveStatusInfo(
                "STATIC",
                0.0,
                config.getCapacity(),
                config.getRefillRate(),
                config.getCapacity(),
                config.getRefillRate(),
                new HashMap<>()
            );
        }
        
        return new AdaptiveStatusInfo(
            "ADAPTIVE",
            adapted.confidence,
            adapted.originalCapacity,
            adapted.originalRefillRate,
            adapted.adaptedCapacity,
            adapted.adaptedRefillRate,
            adapted.reasoning
        );
    }

    /**
     * Get adaptive status for all tracked keys.
     */
    public Map<String, AdaptiveStatusInfo> getAllStatuses() {
        Map<String, AdaptiveStatusInfo> statuses = new HashMap<>();
        Set<String> allKeys = getActiveKeys();
        for (String key : allKeys) {
            statuses.put(key, getStatus(key));
        }
        return statuses;
    }
    
    /**
     * Set manual override for a key.
     */
    public void setOverride(String key, AdaptationOverride override) {
        manualOverrides.put(key, override);
        logger.info("Manual override set for key {}: capacity={}, refillRate={}", 
                   key, override.capacity, override.refillRate);
    }
    
    /**
     * Remove manual override for a key.
     */
    public void removeOverride(String key) {
        manualOverrides.remove(key);
        logger.info("Manual override removed for key: {}", key);
    }
    
    /**
     * Get adapted limits for a key.
     */
    public AdaptedLimits getAdaptedLimits(String key) {
        AdaptationOverride override = manualOverrides.get(key);
        if (override != null) {
            RateLimitConfig originalConfig = configurationResolver.getBaseConfig(key);
            return new AdaptedLimits(
                originalConfig.getCapacity(),
                originalConfig.getRefillRate(),
                override.capacity,
                override.refillRate,
                Instant.now(),
                Map.of("decision", "Manual override active"),
                1.0
            );
        }
        return adaptedLimits.get(key);
    }
    
    /**
     * Record traffic event for analysis.
     */
    public void recordTrafficEvent(String key, int tokensRequested, boolean allowed) {
        if (!enabled) {
            return;
        }
        trackedKeys.add(key);
        userMetricsModeler.recordRequest(key, tokensRequested, allowed);
    }
    
    /**
     * Adaptive status information.
     */
    public static class AdaptiveStatusInfo {
        public final String mode;
        public final double confidence;
        public final int originalCapacity;
        public final int originalRefillRate;
        public final int currentCapacity;
        public final int currentRefillRate;
        public final Map<String, String> reasoning;
        
        public AdaptiveStatusInfo(String mode, double confidence, 
                                 int originalCapacity, int originalRefillRate,
                                 int currentCapacity, int currentRefillRate,
                                 Map<String, String> reasoning) {
            this.mode = mode;
            this.confidence = confidence;
            this.originalCapacity = originalCapacity;
            this.originalRefillRate = originalRefillRate;
            this.currentCapacity = currentCapacity;
            this.currentRefillRate = currentRefillRate;
            this.reasoning = reasoning;
        }
    }
    
    /**
     * Adapted limits holder.
     */
    public static class AdaptedLimits {
        public final int originalCapacity;
        public final int originalRefillRate;
        public final int adaptedCapacity;
        public final int adaptedRefillRate;
        public final Instant timestamp;
        public final Map<String, String> reasoning;
        public final double confidence;
        
        public AdaptedLimits(int originalCapacity, int originalRefillRate,
                           int adaptedCapacity, int adaptedRefillRate,
                           Instant timestamp, Map<String, String> reasoning,
                           double confidence) {
            this.originalCapacity = originalCapacity;
            this.originalRefillRate = originalRefillRate;
            this.adaptedCapacity = adaptedCapacity;
            this.adaptedRefillRate = adaptedRefillRate;
            this.timestamp = timestamp;
            this.reasoning = reasoning;
            this.confidence = confidence;
        }
    }
    
    /**
     * Manual override configuration.
     */
    public static class AdaptationOverride {
        public final int capacity;
        public final int refillRate;
        public final String reason;
        
        public AdaptationOverride(int capacity, int refillRate, String reason) {
            this.capacity = capacity;
            this.refillRate = refillRate;
            this.reason = reason;
        }
    }
}