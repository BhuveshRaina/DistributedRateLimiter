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

class RedisFixedWindowTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    private RedisFixedWindow fixedWindow;
    private final String testKey = "test-fixed-window";
    private final int capacity = 30;
    private final int refillRate = 30;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        fixedWindow = new RedisFixedWindow(testKey, capacity, refillRate, redisTemplate);
    }

    @Test
    void testTryConsumeWithResult_Success() {
        // Result: {success, remaining_tokens, current_count, window_start, current_time}
        List<Object> redisResult = Arrays.asList(1L, 25L, 5L, System.currentTimeMillis() - 100, System.currentTimeMillis());
        
        when(redisTemplate.execute(
            any(RedisScript.class), 
            eq(Collections.singletonList(testKey)), 
            eq(capacity), eq(1000L), eq(5), anyLong()
        )).thenReturn(redisResult);

        RateLimiter.ConsumptionResult result = fixedWindow.tryConsumeWithResult(5);

        assertTrue(result.allowed);
        assertEquals(25, result.remainingTokens);
        assertEquals(capacity, result.capacity);
    }

    @Test
    void testTryConsumeWithResult_Denied() {
        List<Object> redisResult = Arrays.asList(0L, 0L, 30L, System.currentTimeMillis() - 500, System.currentTimeMillis());
        
        when(redisTemplate.execute(
            any(RedisScript.class), 
            eq(Collections.singletonList(testKey)), 
            eq(capacity), eq(1000L), eq(1), anyLong()
        )).thenReturn(redisResult);

        RateLimiter.ConsumptionResult result = fixedWindow.tryConsumeWithResult(1);

        assertFalse(result.allowed);
        assertEquals(0, result.remainingTokens);
    }

    @Test
    void testGetCurrentTokens() {
        List<Object> redisResult = Arrays.asList(1L, 12L, 18L, System.currentTimeMillis() - 200, System.currentTimeMillis());
        
        when(redisTemplate.execute(
            any(RedisScript.class), 
            eq(Collections.singletonList(testKey)), 
            eq(capacity), eq(1000L), eq(0), anyLong()
        )).thenReturn(redisResult);

        int currentTokens = fixedWindow.getCurrentTokens();

        assertEquals(12, currentTokens);
    }

    @Test
    void testGetCapacity() {
        assertEquals(capacity, fixedWindow.getCapacity());
    }
}
