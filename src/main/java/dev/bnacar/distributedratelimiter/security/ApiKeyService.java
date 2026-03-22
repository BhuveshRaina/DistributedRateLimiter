package dev.bnacar.distributedratelimiter.security;

import dev.bnacar.distributedratelimiter.config.SecurityConfiguration;
import dev.bnacar.distributedratelimiter.monitoring.MetricsService;
import dev.bnacar.distributedratelimiter.ratelimit.RateLimiterService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;

@Service
public class ApiKeyService {
    private static final Logger logger = LoggerFactory.getLogger(ApiKeyService.class);

    private final SecurityConfiguration securityConfiguration;
    private final ApiKeyRepository apiKeyRepository;
    private final RateLimiterService rateLimiterService;
    private final MetricsService metricsService;

    @Autowired
    public ApiKeyService(SecurityConfiguration securityConfiguration, 
                         ApiKeyRepository apiKeyRepository,
                         RateLimiterService rateLimiterService,
                         MetricsService metricsService) {
        this.securityConfiguration = securityConfiguration;
        this.apiKeyRepository = apiKeyRepository;
        this.rateLimiterService = rateLimiterService;
        this.metricsService = metricsService;
    }

    /**
     * Initialize Redis with valid keys from security configuration.
     */
    @PostConstruct
    public void init() {
        if (securityConfiguration.getApiKeys().isEnabled()) {
            List<String> validKeys = securityConfiguration.getApiKeys().getValidKeys();
            if (validKeys != null) {
                for (String key : validKeys) {
                    if (!apiKeyRepository.exists(key)) {
                        apiKeyRepository.addApiKey(key, "Initial Config Key", "Pre-configured system key");
                        logger.info("Initialized Redis with API key from security configuration");
                    }
                }
            }
        }
    }

    /**
     * Validates an API key if API key authentication is enabled
     * @param apiKey the API key to validate
     * @return true if the API key is valid or if API key authentication is disabled, false otherwise
     */
    public boolean isValidApiKey(String apiKey) {
        if (!securityConfiguration.getApiKeys().isEnabled()) {
            return true; // API key validation is disabled
        }

        if (!StringUtils.hasText(apiKey)) {
            return false; // API key is required when enabled
        }

        // Check Redis repository
        return apiKeyRepository.exists(apiKey);
    }

    /**
     * Checks if API key authentication is required
     * @return true if API key authentication is enabled
     */
    public boolean isApiKeyRequired() {
        return securityConfiguration.getApiKeys().isEnabled();
    }

    /**
     * Get all currently valid API keys.
     */
    public Set<String> getAllApiKeys() {
        return apiKeyRepository.getAllApiKeys();
    }

    /**
     * Add a new API key with metadata.
     */
    public void addApiKey(String apiKey, String name, String description) {
        if (org.springframework.util.StringUtils.hasText(apiKey)) {
            apiKeyRepository.addApiKey(apiKey, name, description);
        }
    }

    /**
     * Get metadata for an API key.
     */
    public java.util.Map<Object, Object> getMetadata(String apiKey) {
        return apiKeyRepository.getMetadata(apiKey);
    }

    /**
     * Add a new API key (legacy).
     */
    public void addApiKey(String apiKey) {
        addApiKey(apiKey, null, null);
    }

    /**
     * Remove an API key.
     */
    public void removeApiKey(String apiKey) {
        if (StringUtils.hasText(apiKey)) {
            apiKeyRepository.removeApiKey(apiKey);
            
            // Also cleanup active buckets and metrics to avoid dashboard mismatch
            rateLimiterService.removeKey(apiKey);
            metricsService.removeKeyMetrics(apiKey);
            
            logger.info("Removed API key, active buckets, and metrics for key: {}", apiKey);
        }
    }
}