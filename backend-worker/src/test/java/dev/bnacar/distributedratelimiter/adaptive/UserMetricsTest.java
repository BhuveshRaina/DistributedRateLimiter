package dev.bnacar.distributedratelimiter.adaptive;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserMetricsTest {

    private UserMetricsModeler modeler;
    private AimdPolicyEngine policyEngine;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        modeler = new UserMetricsModeler(redisTemplate);
        policyEngine = new AimdPolicyEngine();
    }

    @Test
    void testSpecificUserMetrics_Calculation() {
        String userKey = "user_123";
        
        // Mock 10 allowed and 10 denied tokens in Redis for this specific user
        Map<Object, Object> mockMetrics = Map.of(
            "allowed", 600L, // 600 tokens allowed in 60s = 10 RPS
            "denied", 600L   // 600 tokens denied in 60s = 10 RPS
        );
        
        when(hashOperations.entries(anyString())).thenReturn(mockMetrics);

        // 1. Verify Modeler calculates 50% denial rate for THIS user
        UserMetrics userMetrics = modeler.getUserMetrics(userKey);
        assertEquals(0.5, userMetrics.getDenialRate(), "User 123 should have a 50% denial rate");
        assertEquals(20.0, userMetrics.getCurrentRequestRate(), 0.01); // (600+600)/60 = 20 RPS

        // 2. Verify Policy Engine sees this user's need
        // System is stressed (E=0.10)
        SystemHealth stressedSystem = SystemHealth.builder().cpuUtilization(0.80).build();
        
        // User has Z=1.0 (Mocked by getUserMetrics for single user)
        AdaptationDecision decision = policyEngine.determineAdaptation(stressedSystem, userMetrics, 100, 20, 100, 20, 5);

        // 3. Verify it triggers a decrease because system is stressed and user is an outlier (intensity=1.0)
        assertTrue(decision.shouldAdapt());
        // Tax = 1 + 1 = 2. Mult = 1 - (0.3 * 0.1 * 2) = 0.94. 100 * 0.94 = 94.
        assertEquals(94, decision.getRecommendedCapacity());
    }
}