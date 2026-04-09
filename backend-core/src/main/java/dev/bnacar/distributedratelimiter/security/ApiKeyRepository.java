package dev.bnacar.distributedratelimiter.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Repository for persisting authentication API keys in Redis.
 */
@Repository
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "ratelimiter.redis.enabled", havingValue = "true", matchIfMissing = true)
public class ApiKeyRepository {
    private static final Logger logger = LoggerFactory.getLogger(ApiKeyRepository.class);
    
    private static final String API_KEYS_SET_KEY = "ratelimiter:api-keys";
    private static final String API_KEY_METADATA_PREFIX = "ratelimiter:api-key-metadata:";

    private final RedisTemplate<String, Object> redisTemplate;

    public ApiKeyRepository(@Qualifier("rateLimiterRedisTemplate") RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Add a valid API key to Redis with metadata.
     */
    public void addApiKey(String apiKey, String name, String description) {
        redisTemplate.opsForSet().add(API_KEYS_SET_KEY, apiKey);
        
        java.util.Map<String, String> metadata = new java.util.HashMap<>();
        metadata.put("name", name != null ? name : "Key " + apiKey.substring(0, Math.min(8, apiKey.length())));
        metadata.put("description", description != null ? description : "Authentication API key");
        metadata.put("createdAt", java.time.Instant.now().toString());
        
        redisTemplate.opsForHash().putAll(API_KEY_METADATA_PREFIX + apiKey, metadata);
        logger.info("Added new API key to Redis with metadata");
    }

    /**
     * Add a valid API key to Redis (legacy).
     */
    public void addApiKey(String apiKey) {
        addApiKey(apiKey, null, null);
    }

    /**
     * Remove an API key from Redis.
     */
    public void removeApiKey(String apiKey) {
        redisTemplate.opsForSet().remove(API_KEYS_SET_KEY, apiKey);
        redisTemplate.delete(API_KEY_METADATA_PREFIX + apiKey);
        logger.info("Removed API key and metadata from Redis");
    }

    /**
     * Get metadata for an API key.
     */
    public java.util.Map<Object, Object> getMetadata(String apiKey) {
        return redisTemplate.opsForHash().entries(API_KEY_METADATA_PREFIX + apiKey);
    }

    /**
     * Check if an API key exists in Redis.
     */
    public boolean exists(String apiKey) {
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(API_KEYS_SET_KEY, apiKey));
    }

    /**
     * Get all valid API keys from Redis.
     */
    public Set<String> getAllApiKeys() {
        Set<Object> members = redisTemplate.opsForSet().members(API_KEYS_SET_KEY);
        if (members == null) {
            return Set.of();
        }
        return members.stream()
                .map(Object::toString)
                .collect(Collectors.toSet());
    }
}
