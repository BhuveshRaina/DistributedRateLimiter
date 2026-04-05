package dev.bnacar.distributedratelimiter.monitoring;

import dev.bnacar.distributedratelimiter.models.MetricsResponse;
import dev.bnacar.distributedratelimiter.ratelimit.RateLimitAlgorithm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

class MetricsServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    
    @Mock
    private RedisConnectionFactory redisConnectionFactory;
    
    @Mock
    private HashOperations<String, Object, Object> hashOperations;
    
    @Mock
    private ValueOperations<String, Object> valueOperations;
    
    @Mock
    private ListOperations<String, Object> listOperations;

    private MetricsService metricsService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        
        // Return empty map instead of null for Redis operations to avoid NullPointerExceptions
        when(hashOperations.entries(anyString())).thenReturn(Collections.emptyMap());
        when(redisTemplate.keys(anyString())).thenReturn(Collections.emptySet());
        
        metricsService = new MetricsService(redisTemplate, redisConnectionFactory);
    }

    @Test
    void testRecordAllowedRequest_UpdatesLocalAndGlobalCounters() {
        String key = "test-user";
        metricsService.recordAllowedRequest(key, RateLimitAlgorithm.TOKEN_BUCKET, 1);
        
        MetricsResponse metrics = metricsService.getMetrics();
        
        assertEquals(1, metrics.getTotalAllowedRequests());
        assertTrue(metrics.getKeyMetrics().containsKey(key));
        assertEquals(1, metrics.getKeyMetrics().get(key).getAllowedRequests());
    }

    @Test
    void testRecordDeniedRequest_UpdatesLocalAndGlobalCounters() {
        String key = "test-user";
        metricsService.recordDeniedRequest(key, RateLimitAlgorithm.FIXED_WINDOW, 1);
        
        MetricsResponse metrics = metricsService.getMetrics();
        
        assertEquals(1, metrics.getTotalDeniedRequests());
        assertTrue(metrics.getKeyMetrics().containsKey(key));
        assertEquals(1, metrics.getKeyMetrics().get(key).getDeniedRequests());
    }

    @Test
    void testRecordProcessingTime_AggregatesCorrectly() {
        String key = "test-user";
        metricsService.recordProcessingTime(key, RateLimitAlgorithm.TOKEN_BUCKET, 100);
        metricsService.recordProcessingTime(key, RateLimitAlgorithm.TOKEN_BUCKET, 150);
        
        MetricsResponse metrics = metricsService.getMetrics();
        
        assertEquals(250, metrics.getTotalProcessingTimeMs());
        assertEquals(250, metrics.getKeyMetrics().get(key).getTotalProcessingTime());
    }

    @Test
    void testClearMetrics_ResetsAll() {
        metricsService.recordAllowedRequest("user1", RateLimitAlgorithm.TOKEN_BUCKET, 1);
        metricsService.clearMetrics();
        
        MetricsResponse metrics = metricsService.getMetrics();
        
        assertEquals(0, metrics.getTotalAllowedRequests());
        assertTrue(metrics.getKeyMetrics().isEmpty());
    }
}
