package dev.bnacar.distributedratelimiter.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisSlidingWindowTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    private RedisSlidingWindow slidingWindow;
    private final String testKey = "test-sliding-window";
    private final int capacity = 50;
    private final int refillRate = 10;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        slidingWindow = new RedisSlidingWindow(testKey, capacity, refillRate, redisTemplate);
    }

    @Test
    void testTryConsumeWithResult_Success() {
        // Result: {success, current_count, capacity, window_size, current_time}
        List<Object> redisResult = Arrays.asList(1L, 10L, (long) capacity, 1000L, System.currentTimeMillis());
        
        when(redisTemplate.execute(
            any(RedisScript.class), 
            eq(Collections.singletonList("sliding_window:" + testKey)), 
            eq(capacity), eq(1000L), eq(5), anyLong()
        )).thenReturn(redisResult);

        RateLimiter.ConsumptionResult result = slidingWindow.tryConsumeWithResult(5);

        assertTrue(result.allowed);
        assertEquals(40, result.remainingTokens); // 50 - 10
        assertEquals(capacity, result.capacity);
    }

    @Test
    void testTryConsumeWithResult_Denied() {
        List<Object> redisResult = Arrays.asList(0L, 50L, (long) capacity, 1000L, System.currentTimeMillis());
        
        when(redisTemplate.execute(
            any(RedisScript.class), 
            eq(Collections.singletonList("sliding_window:" + testKey)), 
            eq(capacity), eq(1000L), eq(1), anyLong()
        )).thenReturn(redisResult);

        RateLimiter.ConsumptionResult result = slidingWindow.tryConsumeWithResult(1);

        assertFalse(result.allowed);
        assertEquals(0, result.remainingTokens);
    }

    @Test
    void testGetCurrentTokens() {
        List<Object> redisResult = Arrays.asList(1L, 20L, (long) capacity, 1000L, System.currentTimeMillis());
        
        when(redisTemplate.execute(
            any(RedisScript.class), 
            eq(Collections.singletonList("sliding_window:" + testKey)), 
            eq(capacity), eq(1000L), eq(0), anyLong()
        )).thenReturn(redisResult);

        int currentTokens = slidingWindow.getCurrentTokens();

        assertEquals(30, currentTokens); // 50 - 20
    }

    @Test
    void testGetCapacity() {
        assertEquals(capacity, slidingWindow.getCapacity());
    }
}
