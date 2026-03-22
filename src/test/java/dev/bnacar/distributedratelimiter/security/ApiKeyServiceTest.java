package dev.bnacar.distributedratelimiter.security;

import dev.bnacar.distributedratelimiter.config.SecurityConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

public class ApiKeyServiceTest {

    private SecurityConfiguration securityConfiguration;
    private ApiKeyRepository apiKeyRepository;
    private ApiKeyService apiKeyService;
    private dev.bnacar.distributedratelimiter.ratelimit.RateLimiterService rateLimiterService;
    private dev.bnacar.distributedratelimiter.monitoring.MetricsService metricsService;

    @BeforeEach
    public void setUp() {
        securityConfiguration = new SecurityConfiguration();
        apiKeyRepository = Mockito.mock(ApiKeyRepository.class);
        rateLimiterService = Mockito.mock(dev.bnacar.distributedratelimiter.ratelimit.RateLimiterService.class);
        metricsService = Mockito.mock(dev.bnacar.distributedratelimiter.monitoring.MetricsService.class);
        apiKeyService = new ApiKeyService(securityConfiguration, apiKeyRepository, rateLimiterService, metricsService);
    }

    @Test
    public void testValidApiKeyWhenEnabled() {
        // Configure valid API keys
        securityConfiguration.getApiKeys().setEnabled(true);
        when(apiKeyRepository.exists("key1")).thenReturn(true);
        when(apiKeyRepository.exists("key2")).thenReturn(true);
        when(apiKeyRepository.exists("premium-key")).thenReturn(true);

        assertTrue(apiKeyService.isValidApiKey("key1"));
        assertTrue(apiKeyService.isValidApiKey("key2"));
        assertTrue(apiKeyService.isValidApiKey("premium-key"));
    }

    @Test
    public void testInvalidApiKeyWhenEnabled() {
        // Configure valid API keys
        securityConfiguration.getApiKeys().setEnabled(true);
        when(apiKeyRepository.exists(anyString())).thenReturn(false);

        assertFalse(apiKeyService.isValidApiKey("invalid-key"));
        assertFalse(apiKeyService.isValidApiKey(""));
        assertFalse(apiKeyService.isValidApiKey(null));
        assertFalse(apiKeyService.isValidApiKey("   "));
    }

    @Test
    public void testApiKeyValidationDisabled() {
        // Disable API key validation
        securityConfiguration.getApiKeys().setEnabled(false);

        // Any key should be valid when validation is disabled
        assertTrue(apiKeyService.isValidApiKey("any-key"));
        assertTrue(apiKeyService.isValidApiKey(""));
        assertTrue(apiKeyService.isValidApiKey(null));
    }

    @Test
    public void testIsApiKeyRequired() {
        securityConfiguration.getApiKeys().setEnabled(true);
        assertTrue(apiKeyService.isApiKeyRequired());

        securityConfiguration.getApiKeys().setEnabled(false);
        assertFalse(apiKeyService.isApiKeyRequired());
    }

    @Test
    public void testEmptyValidKeysListWhenEnabled() {
        securityConfiguration.getApiKeys().setEnabled(true);
        securityConfiguration.getApiKeys().setValidKeys(Arrays.asList());

        assertFalse(apiKeyService.isValidApiKey("any-key"));
    }
}