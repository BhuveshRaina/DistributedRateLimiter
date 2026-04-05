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

class RedisTokenBucketTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    private RedisTokenBucket tokenBucket;
    private final String testKey = "test-token-bucket";
    private final int capacity = 100;
    private final int refillRate = 10;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        tokenBucket = new RedisTokenBucket(testKey, capacity, refillRate, redisTemplate);
    }

    @Test
    void testTryConsumeWithResult_Success() {
        // Mock successful consumption: {allowed (1), remaining_tokens, capacity, refill_rate, last_refill}
        List<Object> redisResult = Arrays.asList(1L, 95L, (long) capacity, (long) refillRate, System.currentTimeMillis());
        
        when(redisTemplate.execute(
            any(RedisScript.class), 
            eq(Collections.singletonList(testKey)), 
            eq(capacity), eq(refillRate), eq(5), anyLong()
        )).thenReturn(redisResult);

        RateLimiter.ConsumptionResult result = tokenBucket.tryConsumeWithResult(5);

        assertTrue(result.allowed);
        assertEquals(95, result.remainingTokens);
        assertEquals(capacity, result.capacity);
    }

    @Test
    void testTryConsumeWithResult_Denied() {
        // Mock denied consumption: {allowed (0), remaining_tokens (2), capacity, refill_rate, last_refill}
        List<Object> redisResult = Arrays.asList(0L, 2L, (long) capacity, (long) refillRate, System.currentTimeMillis());
        
        when(redisTemplate.execute(
            any(RedisScript.class), 
            eq(Collections.singletonList(testKey)), 
            eq(capacity), eq(refillRate), eq(5), anyLong()
        )).thenReturn(redisResult);

        RateLimiter.ConsumptionResult result = tokenBucket.tryConsumeWithResult(5);

        assertFalse(result.allowed);
        assertEquals(2, result.remainingTokens);
    }

    @Test
    void testTryConsumeWithResult_InvalidTokens() {
        RateLimiter.ConsumptionResult result = tokenBucket.tryConsumeWithResult(0);
        assertFalse(result.allowed);
        
        result = tokenBucket.tryConsumeWithResult(-1);
        assertFalse(result.allowed);
    }

    @Test
    void testGetCurrentTokens() {
        // Mocking state query (requested 0 tokens)
        List<Object> redisResult = Arrays.asList(1L, 50L, (long) capacity, (long) refillRate, System.currentTimeMillis());
        when(redisTemplate.execute(
            any(RedisScript.class), 
            eq(Collections.singletonList(testKey)), 
            eq(capacity), eq(refillRate), eq(0), anyLong()
        )).thenReturn(redisResult);

        int currentTokens = tokenBucket.getCurrentTokens();

        assertEquals(50, currentTokens);
    }

    @Test
    void testGetCapacity() {
        assertEquals(capacity, tokenBucket.getCapacity());
    }

    @Test
    void testGetRefillRate() {
        assertEquals(refillRate, tokenBucket.getRefillRate());
    }
}
