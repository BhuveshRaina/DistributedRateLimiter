package dev.bnacar.distributedratelimiter.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.HashMap;

import dev.bnacar.distributedratelimiter.config.ConfigRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.HashMap;

@Configuration
@ConfigurationProperties(prefix = "ratelimiter")
public class RateLimiterConfiguration {
    private static final Logger logger = LoggerFactory.getLogger(RateLimiterConfiguration.class);
    
    // Default configuration
    private int capacity = 10;
    private int refillRate = 2;
    private long cleanupIntervalMs = 60000; // 60 seconds
    private RateLimitAlgorithm algorithm = RateLimitAlgorithm.TOKEN_BUCKET;
    
    // Per-key overrides: key -> config properties
    private Map<String, KeyConfig> keys = new HashMap<>();
    
    // Pattern-based configurations: pattern -> config properties
    private Map<String, KeyConfig> patterns = new HashMap<>();

    @Autowired(required = false)
    private ConfigRepository configRepository;

    /**
     * Load state from Redis after initialization.
     */
    @PostConstruct
    public void init() {
        if (configRepository != null) {
            try {
                loadFromRedis();
                logger.info("Configuration loaded from Redis successfully");
            } catch (Exception e) {
                logger.warn("Failed to load configuration from Redis, using defaults: {}", e.getMessage());
            }
        }
    }

    private void loadFromRedis() {
        Map<Object, Object> defaultConfig = configRepository.loadDefaultConfig();
        if (!defaultConfig.isEmpty()) {
            this.capacity = (Integer) defaultConfig.get("capacity");
            this.refillRate = (Integer) defaultConfig.get("refillRate");
            this.cleanupIntervalMs = ((Number) defaultConfig.get("cleanupIntervalMs")).longValue();
            this.algorithm = RateLimitAlgorithm.valueOf((String) defaultConfig.get("algorithm"));
        }
        
        // Ensure that these are not re-initialized as new HashMaps, but merged with existing properties
        // This is crucial if some configs come from application.properties and others from Redis
        Map<String, KeyConfig> loadedKeys = configRepository.loadKeyConfigs();
        if (!loadedKeys.isEmpty()) {
            this.keys.putAll(loadedKeys);
        }

        Map<String, KeyConfig> loadedPatterns = configRepository.loadPatternConfigs();
        if (!loadedPatterns.isEmpty()) {
            this.patterns.putAll(loadedPatterns);
        }
    }

    private void saveDefaultToRedis() {
        if (configRepository != null) {
            configRepository.saveDefaultConfig(capacity, refillRate, cleanupIntervalMs, algorithm);
        }
    }
    
    public static class KeyConfig {
        private int capacity;
        private int refillRate;
        private Long cleanupIntervalMs;
        private RateLimitAlgorithm algorithm;
        
        public int getCapacity() {
            return capacity;
        }
        
        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }
        
        public int getRefillRate() {
            return refillRate;
        }
        
        public void setRefillRate(int refillRate) {
            this.refillRate = refillRate;
        }
        
        public Long getCleanupIntervalMs() {
            return cleanupIntervalMs;
        }
        
        public void setCleanupIntervalMs(Long cleanupIntervalMs) {
            this.cleanupIntervalMs = cleanupIntervalMs;
        }
        
        public RateLimitAlgorithm getAlgorithm() {
            return algorithm;
        }
        
        public void setAlgorithm(RateLimitAlgorithm algorithm) {
            this.algorithm = algorithm;
        }
    }
    
    // Default getters and setters
    public int getCapacity() {
        return capacity;
    }
    
    public void setCapacity(int capacity) {
        this.capacity = capacity;
        saveDefaultToRedis();
    }
    
    public int getRefillRate() {
        return refillRate;
    }
    
    public void setRefillRate(int refillRate) {
        this.refillRate = refillRate;
        saveDefaultToRedis();
    }
    
    public long getCleanupIntervalMs() {
        return cleanupIntervalMs;
    }
    
    public void setCleanupIntervalMs(long cleanupIntervalMs) {
        this.cleanupIntervalMs = cleanupIntervalMs;
        saveDefaultToRedis();
    }
    
    public RateLimitAlgorithm getAlgorithm() {
        return algorithm;
    }
    
    public void setAlgorithm(RateLimitAlgorithm algorithm) {
        this.algorithm = algorithm;
        saveDefaultToRedis();
    }
    
    // Per-key and pattern configuration getters and setters
    public Map<String, KeyConfig> getKeys() {
        return new HashMap<>(keys);
    }
    
    public void setKeys(Map<String, KeyConfig> keys) {
        this.keys = keys != null ? new HashMap<>(keys) : new HashMap<>();
    }
    
    public Map<String, KeyConfig> getPatterns() {
        return new HashMap<>(patterns);
    }
    
    public void setPatterns(Map<String, KeyConfig> patterns) {
        this.patterns = patterns != null ? new HashMap<>(patterns) : new HashMap<>();
    }
    
    // Methods for safe modification of keys and patterns
    public void putKey(String key, KeyConfig config) {
        this.keys.put(key, config);
        if (configRepository != null) {
            configRepository.saveKeyConfig(key, config);
        }
    }
    
    public KeyConfig removeKey(String key) {
        KeyConfig removed = this.keys.remove(key);
        if (configRepository != null && removed != null) {
            configRepository.deleteKeyConfig(key);
        }
        return removed;
    }
    
    public void putPattern(String pattern, KeyConfig config) {
        this.patterns.put(pattern, config);
        if (configRepository != null) {
            configRepository.savePatternConfig(pattern, config);
        }
    }
    
    public KeyConfig removePattern(String pattern) {
        KeyConfig removed = this.patterns.remove(pattern);
        if (configRepository != null && removed != null) {
            configRepository.deletePatternConfig(pattern);
        }
        return removed;
    }
    
    /**
     * Get the default rate limit configuration.
     */
    public RateLimitConfig getDefaultConfig() {
        return new RateLimitConfig(capacity, refillRate, cleanupIntervalMs, algorithm);
    }
}