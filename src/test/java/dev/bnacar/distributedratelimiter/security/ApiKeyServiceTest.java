package dev.bnacar.distributedratelimiter.security;

import dev.bnacar.distributedratelimiter.config.SecurityConfiguration;
import dev.bnacar.distributedratelimiter.monitoring.MetricsService;
import dev.bnacar.distributedratelimiter.ratelimit.RateLimiterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApiKeyServiceTest {

    @Mock
    private SecurityConfiguration securityConfiguration;
    
    @Mock
    private ApiKeyRepository apiKeyRepository;
    
    @Mock
    private RateLimiterService rateLimiterService;
    
    @Mock
    private MetricsService metricsService;

    private ApiKeyService apiKeyService;
    private SecurityConfiguration.ApiKeys apiKeysProps;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        apiKeysProps = new SecurityConfiguration.ApiKeys();
        when(securityConfiguration.getApiKeys()).thenReturn(apiKeysProps);
        
        apiKeyService = new ApiKeyService(securityConfiguration, apiKeyRepository, rateLimiterService, metricsService);
    }

    @Test
    void testIsValidApiKey_WhenDisabled_ReturnsTrue() {
        apiKeysProps.setEnabled(false);
        assertTrue(apiKeyService.isValidApiKey("any-key"));
    }

    @Test
    void testIsValidApiKey_WhenEnabledAndKeyValid_ReturnsTrue() {
        apiKeysProps.setEnabled(true);
        String validKey = "valid-key";
        when(apiKeyRepository.exists(validKey)).thenReturn(true);
        
        assertTrue(apiKeyService.isValidApiKey(validKey));
    }

    @Test
    void testIsValidApiKey_WhenEnabledAndKeyInvalid_ReturnsFalse() {
        apiKeysProps.setEnabled(true);
        String invalidKey = "invalid-key";
        when(apiKeyRepository.exists(invalidKey)).thenReturn(false);
        
        assertFalse(apiKeyService.isValidApiKey(invalidKey));
    }

    @Test
    void testIsValidApiKey_WhenEnabledAndKeyBlank_ReturnsFalse() {
        apiKeysProps.setEnabled(true);
        assertFalse(apiKeyService.isValidApiKey(""));
        assertFalse(apiKeyService.isValidApiKey(null));
    }

    @Test
    void testRemoveApiKey_TriggersCleanup() {
        String keyToRemove = "old-key";
        apiKeyService.removeApiKey(keyToRemove);
        
        verify(apiKeyRepository).removeApiKey(keyToRemove);
        verify(rateLimiterService).removeKey(keyToRemove);
        verify(metricsService).removeKeyMetrics(keyToRemove);
    }
}
