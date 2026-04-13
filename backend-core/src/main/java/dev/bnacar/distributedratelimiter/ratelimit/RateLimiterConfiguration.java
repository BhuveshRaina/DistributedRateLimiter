package dev.bnacar.distributedratelimiter.ratelimit;

import dev.bnacar.distributedratelimiter.config.ConfigRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

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

    private boolean initialized = false;

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
        this.initialized = true;
    }

    private void loadFromRedis() {
        try {
            Map<Object, Object> defaultConfig = configRepository.loadDefaultConfig();
            if (defaultConfig != null && !defaultConfig.isEmpty()) {
                if (defaultConfig.get("capacity") != null) {
                    this.capacity = (Integer) defaultConfig.get("capacity");
                }
                if (defaultConfig.get("refillRate") != null) {
                    this.refillRate = (Integer) defaultConfig.get("refillRate");
                }
                if (defaultConfig.get("cleanupIntervalMs") != null) {
                    this.cleanupIntervalMs = ((Number) defaultConfig.get("cleanupIntervalMs")).longValue();
                }
                if (defaultConfig.get("algorithm") != null) {
                    this.algorithm = RateLimitAlgorithm.valueOf((String) defaultConfig.get("algorithm"));
                }
            }
            
            // Ensure that these are not re-initialized as new HashMaps, but merged with existing properties
            // This is crucial if some configs come from application.properties and others from Redis
            Map<String, KeyConfig> loadedKeys = configRepository.loadKeyConfigs();
            if (loadedKeys != null && !loadedKeys.isEmpty()) {
                this.keys.putAll(loadedKeys);
            }

            Map<String, KeyConfig> loadedPatterns = configRepository.loadPatternConfigs();
            if (loadedPatterns != null && !loadedPatterns.isEmpty()) {
                this.patterns.putAll(loadedPatterns);
            }
        } catch (Exception e) {
            logger.warn("Error while loading configuration from Redis: {}", e.getMessage());
        }
    }

    private void saveDefaultToRedis() {
        if (configRepository != null && initialized) {
            try {
                configRepository.saveDefaultConfig(capacity, refillRate, cleanupIntervalMs, algorithm);
            } catch (Exception e) {
                logger.warn("Failed to save default configuration to Redis: {}", e.getMessage());
            }
        }
    }
    
    public static class KeyConfig {
        private int capacity;
        private int refillRate;
        private Long cleanupIntervalMs;
        private RateLimitAlgorithm algorithm;
        private boolean adaptiveEnabled = false;
        private boolean shadowMode = false;
        
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

        public boolean isAdaptiveEnabled() {
            return adaptiveEnabled;
        }

        public void setAdaptiveEnabled(boolean adaptiveEnabled) {
            this.adaptiveEnabled = adaptiveEnabled;
        }

        public boolean isShadowMode() {
            return shadowMode;
        }

        public void setShadowMode(boolean shadowMode) {
            this.shadowMode = shadowMode;
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
        if (configRepository != null && initialized) {
            try {
                configRepository.saveKeyConfig(key, config);
            } catch (Exception e) {
                logger.warn("Failed to save key config to Redis for {}: {}", key, e.getMessage());
            }
        }
    }
    
    public KeyConfig removeKey(String key) {
        KeyConfig removed = this.keys.remove(key);
        if (configRepository != null && initialized && removed != null) {
            try {
                configRepository.deleteKeyConfig(key);
            } catch (Exception e) {
                logger.warn("Failed to delete key config from Redis for {}: {}", key, e.getMessage());
            }
        }
        return removed;
    }
    
    public void putPattern(String pattern, KeyConfig config) {
        this.patterns.put(pattern, config);
        if (configRepository != null && initialized) {
            try {
                configRepository.savePatternConfig(pattern, config);
            } catch (Exception e) {
                logger.warn("Failed to save pattern config to Redis for {}: {}", pattern, e.getMessage());
            }
        }
    }
    
    public KeyConfig removePattern(String pattern) {
        KeyConfig removed = this.patterns.remove(pattern);
        if (configRepository != null && initialized && removed != null) {
            try {
                configRepository.deletePatternConfig(pattern);
            } catch (Exception e) {
                logger.warn("Failed to delete pattern config from Redis for {}: {}", pattern, e.getMessage());
            }
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