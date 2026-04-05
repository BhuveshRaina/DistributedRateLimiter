package dev.bnacar.distributedratelimiter.ratelimit;

import dev.bnacar.distributedratelimiter.monitoring.MetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DistributedRateLimiterServiceTest {

    private DistributedRateLimiterService service;
    
    @Mock
    private ConfigurationResolver configurationResolver;
    
    @Mock
    private RateLimiterBackend primaryBackend;
    
    @Mock
    private MetricsService metricsService;
    
    @Mock
    private RateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new DistributedRateLimiterService(configurationResolver, primaryBackend);
    }

    @Test
    void testIsAllowed_WhenRedisAvailable_ReturnsBackendResult() {
        String key = "test-key";
        RateLimitConfig config = new RateLimitConfig(10, 2);
        
        when(configurationResolver.resolveBaseKey(key)).thenReturn(key);
        when(configurationResolver.resolveConfig(key)).thenReturn(config);
        when(primaryBackend.isAvailable()).thenReturn(true);
        when(primaryBackend.getRateLimiter(eq(key), any())).thenReturn(rateLimiter);
        when(rateLimiter.getCapacity()).thenReturn(10);
        when(rateLimiter.getRefillRate()).thenReturn(2);
        when(rateLimiter.tryConsumeWithResult(1)).thenReturn(new RateLimiter.ConsumptionResult(true, 9, 10));

        boolean allowed = service.isAllowed(key, 1);
        
        assertTrue(allowed);
        verify(primaryBackend).getRateLimiter(eq(key), any());
    }

    @Test
    void testIsAllowed_WhenRedisUnavailable_ReturnsDenied() {
        String key = "test-key";
        RateLimitConfig config = new RateLimitConfig(10, 2);
        
        when(configurationResolver.resolveBaseKey(key)).thenReturn(key);
        when(configurationResolver.resolveConfig(key)).thenReturn(config);
        when(primaryBackend.isAvailable()).thenReturn(false);

        boolean allowed = service.isAllowed(key, 1);
        
        assertFalse(allowed);
        verify(primaryBackend, never()).getRateLimiter(any(), any());
    }

    @Test
    void testIsAllowed_WhenBackendThrowsException_ReturnsDenied() {
        String key = "test-key";
        
        when(configurationResolver.resolveBaseKey(key)).thenReturn(key);
        when(primaryBackend.isAvailable()).thenReturn(true);
        when(primaryBackend.getRateLimiter(any(), any())).thenThrow(new RuntimeException("Redis connection failed"));

        boolean allowed = service.isAllowed(key, 1);
        
        assertFalse(allowed);
    }
}
