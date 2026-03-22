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
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

public class MetricsServiceTest {

    private MetricsService metricsService;
    
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

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        
        metricsService = new MetricsService(redisTemplate, redisConnectionFactory);
    }

    @Test
    void recordAllowedRequest_ShouldIncrementCounters() {
        String key = "user1";
        RateLimitAlgorithm algo = RateLimitAlgorithm.TOKEN_BUCKET;
        
        metricsService.recordAllowedRequest(key, algo, 1);
        metricsService.recordAllowedRequest(key, algo, 1);
        
        MetricsResponse metrics = metricsService.getMetrics();
        
        assertEquals(2, metrics.getTotalAllowedRequests());
        assertEquals(0, metrics.getTotalDeniedRequests());
        assertTrue(metrics.getKeyMetrics().containsKey(key));
        assertEquals(2, metrics.getKeyMetrics().get(key).getAllowedRequests());
        assertEquals(0, metrics.getKeyMetrics().get(key).getDeniedRequests());
        
        assertTrue(metrics.getPerAlgorithmMetrics().containsKey(algo.name()));
        assertEquals(2, metrics.getPerAlgorithmMetrics().get(algo.name()).getAllowedRequests());
        assertEquals(0, metrics.getPerAlgorithmMetrics().get(algo.name()).getDeniedRequests());
        
        assertEquals(2, metrics.getRecentEvents().size());
        assertTrue(metrics.getRecentEvents().stream().allMatch(e -> e.isAllowed() && e.getKey().equals(key)));
    }

    @Test
    void recordDeniedRequest_ShouldIncrementCounters() {
        String key = "user1";
        RateLimitAlgorithm algo = RateLimitAlgorithm.FIXED_WINDOW;
        
        metricsService.recordDeniedRequest(key, algo, 1);
        metricsService.recordDeniedRequest(key, algo, 1);
        metricsService.recordDeniedRequest(key, algo, 1);
        
        MetricsResponse metrics = metricsService.getMetrics();
        
        assertEquals(0, metrics.getTotalAllowedRequests());
        assertEquals(3, metrics.getTotalDeniedRequests());
        assertTrue(metrics.getKeyMetrics().containsKey(key));
        assertEquals(0, metrics.getKeyMetrics().get(key).getAllowedRequests());
        assertEquals(3, metrics.getKeyMetrics().get(key).getDeniedRequests());

        assertTrue(metrics.getPerAlgorithmMetrics().containsKey(algo.name()));
        assertEquals(0, metrics.getPerAlgorithmMetrics().get(algo.name()).getAllowedRequests());
        assertEquals(3, metrics.getPerAlgorithmMetrics().get(algo.name()).getDeniedRequests());

        assertEquals(3, metrics.getRecentEvents().size());
        assertTrue(metrics.getRecentEvents().stream().allMatch(e -> !e.isAllowed() && e.getKey().equals(key)));
    }

    @Test
    void recordMixedRequests_ShouldTrackBothTypes() {
        String key1 = "user1";
        String key2 = "user2";
        RateLimitAlgorithm algo1 = RateLimitAlgorithm.TOKEN_BUCKET;
        RateLimitAlgorithm algo2 = RateLimitAlgorithm.SLIDING_WINDOW;
        
        metricsService.recordAllowedRequest(key1, algo1, 1);
        metricsService.recordAllowedRequest(key1, algo1, 1);
        metricsService.recordDeniedRequest(key1, algo1, 1);
        
        metricsService.recordAllowedRequest(key2, algo2, 1);
        metricsService.recordDeniedRequest(key2, algo2, 1);
        metricsService.recordDeniedRequest(key2, algo2, 1);
        
        MetricsResponse metrics = metricsService.getMetrics();
        
        assertEquals(3, metrics.getTotalAllowedRequests());
        assertEquals(3, metrics.getTotalDeniedRequests());
        
        assertEquals(2, metrics.getKeyMetrics().get(key1).getAllowedRequests());
        assertEquals(1, metrics.getKeyMetrics().get(key1).getDeniedRequests());
        
        assertEquals(1, metrics.getKeyMetrics().get(key2).getAllowedRequests());
        assertEquals(2, metrics.getKeyMetrics().get(key2).getDeniedRequests());

        assertEquals(2, metrics.getPerAlgorithmMetrics().get(algo1.name()).getAllowedRequests());
        assertEquals(1, metrics.getPerAlgorithmMetrics().get(algo1.name()).getDeniedRequests());
        assertEquals(1, metrics.getPerAlgorithmMetrics().get(algo2.name()).getAllowedRequests());
        assertEquals(2, metrics.getPerAlgorithmMetrics().get(algo2.name()).getDeniedRequests());

        assertEquals(6, metrics.getRecentEvents().size());
    }

    @Test
    void setRedisConnected_ShouldUpdateConnectionStatus() {
        assertFalse(metricsService.isRedisConnected());
        
        metricsService.setRedisConnected(true);
        assertTrue(metricsService.isRedisConnected());
        
        MetricsResponse metrics = metricsService.getMetrics();
        assertTrue(metrics.isRedisConnected());
        
        metricsService.setRedisConnected(false);
        assertFalse(metricsService.isRedisConnected());
        
        metrics = metricsService.getMetrics();
        assertFalse(metrics.isRedisConnected());
    }

    @Test
    void clearMetrics_ShouldResetAllCounters() {
        String key = "user1";
        metricsService.recordAllowedRequest(key, RateLimitAlgorithm.TOKEN_BUCKET, 1);
        metricsService.recordDeniedRequest(key, RateLimitAlgorithm.TOKEN_BUCKET, 1);
        metricsService.setRedisConnected(true);
        
        MetricsResponse metrics = metricsService.getMetrics();
        assertEquals(1, metrics.getTotalAllowedRequests());
        assertEquals(1, metrics.getTotalDeniedRequests());
        assertTrue(metrics.isRedisConnected());
        
        metricsService.clearMetrics();
        
        metrics = metricsService.getMetrics();
        assertEquals(0, metrics.getTotalAllowedRequests());
        assertEquals(0, metrics.getTotalDeniedRequests());
        assertTrue(metrics.getKeyMetrics().isEmpty());
        assertTrue(metrics.getPerAlgorithmMetrics().values().stream().allMatch(a -> a.getAllowedRequests() == 0));
        assertTrue(metrics.getRecentEvents().isEmpty());
        // Redis connection status should not be affected by clearMetrics
        assertTrue(metrics.isRedisConnected());
    }

    @Test
    void getMetrics_InitialState_ShouldReturnEmptyMetrics() {
        MetricsResponse metrics = metricsService.getMetrics();
        
        assertEquals(0, metrics.getTotalAllowedRequests());
        assertEquals(0, metrics.getTotalDeniedRequests());
        assertFalse(metrics.isRedisConnected());
        assertTrue(metrics.getKeyMetrics().isEmpty());
        assertTrue(metrics.getPerAlgorithmMetrics().values().stream().allMatch(a -> a.getAllowedRequests() == 0));
        assertTrue(metrics.getRecentEvents().isEmpty());
    }

    @Test
    void lastAccessTime_ShouldBeUpdatedOnRequests() {
        String key = "user1";
        long beforeTime = System.currentTimeMillis();
        
        metricsService.recordAllowedRequest(key, RateLimitAlgorithm.TOKEN_BUCKET, 1);
        
        MetricsResponse metrics = metricsService.getMetrics();
        long afterTime = System.currentTimeMillis();
        
        long lastAccessTime = metrics.getKeyMetrics().get(key).getLastAccessTime();
        assertTrue(lastAccessTime >= beforeTime);
        assertTrue(lastAccessTime <= afterTime);
    }

    @Test
    void removeKeyMetrics_ShouldRemoveSpecificKeyData() {
        String key1 = "user1";
        String key2 = "user2";
        RateLimitAlgorithm algo = RateLimitAlgorithm.TOKEN_BUCKET;

        metricsService.recordAllowedRequest(key1, algo, 1);
        metricsService.recordDeniedRequest(key1, algo, 1);
        metricsService.recordAllowedRequest(key2, algo, 1);

        assertEquals(2, metricsService.getMetrics().getKeyMetrics().size());
        assertTrue(metricsService.getMetrics().getKeyMetrics().containsKey(key1));
        assertTrue(metricsService.getMetrics().getKeyMetrics().containsKey(key2));

        metricsService.removeKeyMetrics(key1);

        assertEquals(1, metricsService.getMetrics().getKeyMetrics().size());
        assertFalse(metricsService.getMetrics().getKeyMetrics().containsKey(key1));
        assertTrue(metricsService.getMetrics().getKeyMetrics().containsKey(key2));

        // Total counts should not change as it's a historical record
        assertEquals(3, metricsService.getMetrics().getTotalAllowedRequests() + metricsService.getMetrics().getTotalDeniedRequests());
        // Algorithm metrics should also not change as they are cumulative
        assertEquals(2, metricsService.getMetrics().getPerAlgorithmMetrics().get(algo.name()).getAllowedRequests());
        assertEquals(1, metricsService.getMetrics().getPerAlgorithmMetrics().get(algo.name()).getDeniedRequests());
    }

    @Test
    void recordProcessingTime_ShouldAggregateCorrectly() {
        String key = "testKey";
        RateLimitAlgorithm algorithm = RateLimitAlgorithm.TOKEN_BUCKET;

        metricsService.recordProcessingTime(key, algorithm, 10);
        metricsService.recordProcessingTime(key, algorithm, 20);

        MetricsResponse metrics = metricsService.getMetrics();

        assertEquals(30, metrics.getTotalProcessingTimeMs());
        assertEquals(30, metrics.getKeyMetrics().get(key).getTotalProcessingTime());
        assertEquals(30, metrics.getPerAlgorithmMetrics().get(algorithm.name()).getTotalProcessingTimeMs());
    }

    @Test
    void recentEvents_ShouldBeLimited() {
        String key = "testKey";
        RateLimitAlgorithm algorithm = RateLimitAlgorithm.TOKEN_BUCKET;

        for (int i = 0; i < MetricsService.MAX_RECENT_EVENTS + 5; i++) {
            metricsService.recordAllowedRequest(key, algorithm, 1);
        }

        MetricsResponse metrics = metricsService.getMetrics();
        assertEquals((int) MetricsService.MAX_RECENT_EVENTS, metrics.getRecentEvents().size());
        // Ensure oldest events are removed
        long oldestTimestamp = metrics.getRecentEvents().get(0).getTimestamp();
        long newestTimestamp = metrics.getRecentEvents().get((int) MetricsService.MAX_RECENT_EVENTS - 1).getTimestamp();
        assertTrue(newestTimestamp >= oldestTimestamp);
    }
}