package dev.bnacar.distributedratelimiter.adaptive;

import dev.bnacar.distributedratelimiter.ratelimit.ConfigurationResolver;
import dev.bnacar.distributedratelimiter.ratelimit.RateLimitConfig;
import dev.bnacar.distributedratelimiter.ratelimit.RateLimiterService;
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
    private final RateLimiterService rateLimiterService;
    
    // Store adaptive configurations and overrides
    private final Map<String, AdaptedLimits> adaptedLimits = new ConcurrentHashMap<>();
    private final Map<String, AdaptationOverride> manualOverrides = new ConcurrentHashMap<>();
    
    // Explicit adaptive targets
    private final Map<String, AdaptiveTarget> adaptiveTargets = new ConcurrentHashMap<>();
    
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
            @Lazy RateLimiterService rateLimiterService,
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
        this.rateLimiterService = rateLimiterService;
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
                // Check priority: if a key matches multiple targets, 
                // actual key based target has higher priority than pattern based.
                // For now, we evaluate all active keys and the generation logic 
                // can decide if it should adapt based on targets.
                
                if (!shouldEvaluate(key)) {
                    continue;
                }

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
     * Check if a key should be evaluated based on adaptive targets.
     */
    private boolean shouldEvaluate(String key) {
        // If it's explicitly tracked, evaluate it
        if (adaptiveTargets.containsKey(key)) {
            return true;
        }

        // Check patterns with lower priority
        for (Map.Entry<String, AdaptiveTarget> entry : adaptiveTargets.entrySet()) {
            if (entry.getValue().isPattern() && key.matches(entry.getKey().replace("*", ".*"))) {
                return true;
            }
        }

        // Fallback to tracked keys if no explicit targets are defined or if we want to learn anyway
        return trackedKeys.contains(key);
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
        
        // Use current adapted limits if present as the base for next adaptation (cumulative AIMD)
        AdaptedLimits currentAdapted = adaptedLimits.get(key);
        int baseCapacity;
        int baseRefillRate;
        
        if (currentAdapted != null) {
            baseCapacity = currentAdapted.adaptedCapacity;
            baseRefillRate = currentAdapted.adaptedRefillRate;
        } else {
            RateLimitConfig baseConfig = configurationResolver.getBaseConfig(key);
            baseCapacity = baseConfig.getCapacity();
            baseRefillRate = baseConfig.getRefillRate();
        }
        
        // Collect signals
        SystemHealth health = metricsCollector.getCurrentHealth();
        UserMetrics userMetrics = userMetricsModeler.getUserMetrics(key);
        
        // Use ML model to generate decision based on CURRENT limits
        return adaptiveModel.predict(health, userMetrics, baseCapacity, baseRefillRate);
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
        keys.addAll(adaptiveTargets.keySet());
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
            
            // Any key that is targeted or has active traffic (tracked) is now considered in ADAPTIVE mode
            // because it is subject to the AIMD policy evaluations immediately.
            if (isTargeted(key) || trackedKeys.contains(key)) {
                Map<String, String> reasoning = new HashMap<>();
                reasoning.put("decision", "Monitoring active");
                reasoning.put("status", "Subject to AIMD policy");
                
                return new AdaptiveStatusInfo(
                    "ADAPTIVE",
                    1.0,
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

    private boolean isTargeted(String key) {
        if (adaptiveTargets.containsKey(key)) return true;
        for (Map.Entry<String, AdaptiveTarget> entry : adaptiveTargets.entrySet()) {
            if (entry.getValue().isPattern() && key.matches(entry.getKey().replace("*", ".*"))) {
                return true;
            }
        }
        return false;
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
     * Add adaptive target with initial configuration.
     */
    public void addAdaptiveTarget(String target, boolean isPattern, Integer capacity, Integer refillRate) {
        adaptiveTargets.put(target, new AdaptiveTarget(target, isPattern));
        
        // Clear any existing adapted limits to force a reset to the new base configuration
        adaptedLimits.remove(target);
        
        // Initial values: use provided or system defaults
        int finalCapacity = (capacity != null) ? capacity : configurationResolver.getBaseConfig(target).getCapacity();
        int finalRefillRate = (refillRate != null) ? refillRate : configurationResolver.getBaseConfig(target).getRefillRate();
        
        // Always register/update in configuration to ensure it shows up in the config page
        if (isPattern) {
            // If it's a pattern, explicitly remove from keys to avoid showing in per-key section
            configurationResolver.removeKeyConfig(target);
            configurationResolver.updatePatternConfig(target, new RateLimitConfig(finalCapacity, finalRefillRate));
        } else {
            // If it's a key, explicitly remove from patterns to avoid showing in pattern section
            configurationResolver.removePatternConfig(target);
            configurationResolver.updateKeyConfig(target, new RateLimitConfig(finalCapacity, finalRefillRate));
        }
        
        // Clear buckets and cache to ensure new config/target takes effect immediately
        configurationResolver.clearCache();
        if (rateLimiterService != null) {
            rateLimiterService.clearBuckets();
        }
        
        logger.info("Added/Updated adaptive target: {} (pattern: {}, baseCapacity: {}, baseRefillRate: {})", 
                   target, isPattern, finalCapacity, finalRefillRate);
    }
    
    /**
     * Overloaded method for backward compatibility.
     */
    public void addAdaptiveTarget(String target, boolean isPattern) {
        addAdaptiveTarget(target, isPattern, null, null);
    }

    /**
     * Remove adaptive target.
     */
    public void removeAdaptiveTarget(String target) {
        AdaptiveTarget targetObj = adaptiveTargets.remove(target);
        adaptedLimits.remove(target);
        trackedKeys.remove(target);
        
        // Also remove configuration override if it was created as part of addAdaptiveTarget
        if (targetObj != null) {
            if (targetObj.isPattern()) {
                configurationResolver.removePatternConfig(target);
            } else {
                configurationResolver.removeKeyConfig(target);
            }
        }
        
        // Clear buckets to ensure it reverts to standard configuration
        if (rateLimiterService != null) {
            rateLimiterService.clearBuckets();
        }
        
        logger.info("Removed adaptive target: {}", target);
    }

    /**
     * Get all adaptive targets.
     */
    public Map<String, AdaptiveTarget> getAdaptiveTargets() {
        return adaptiveTargets;
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

    /**
     * Adaptive target configuration.
     */
    public static class AdaptiveTarget {
        private final String target;
        private final boolean isPattern;

        public AdaptiveTarget(String target, boolean isPattern) {
            this.target = target;
            this.isPattern = isPattern;
        }

        public String getTarget() { return target; }
        public boolean isPattern() { return isPattern; }
    }
}