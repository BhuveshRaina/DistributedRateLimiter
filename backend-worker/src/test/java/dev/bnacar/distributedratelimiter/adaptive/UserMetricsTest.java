package dev.bnacar.distributedratelimiter.adaptive;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserMetricsTest {

    private UserMetricsModeler modeler;
    private AimdPolicyEngine policyEngine;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private ZSetOperations<String, Object> zSetOperations;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        modeler = new UserMetricsModeler(redisTemplate);
        policyEngine = new AimdPolicyEngine();
    }

    @Test
    void testSpecificUserMetrics_Calculation() {
        String userKey = "user_123";
        
        // Mock 10 allowed and 10 denied requests in Redis for this specific user
        Set<Object> mockEvents = Set.of(
            "A:1:123456789", "A:1:123456790", "A:1:123456791", "A:1:123456792", "A:1:123456793",
            "A:1:123456794", "A:1:123456795", "A:1:123456796", "A:1:123456797", "A:1:123456798",
            "D:1:123456799", "D:1:123456800", "D:1:123456801", "D:1:123456802", "D:1:123456803",
            "D:1:123456804", "D:1:123456805", "D:1:123456806", "D:1:123456807", "D:1:123456808"
        );
        
        when(zSetOperations.rangeByScore(eq("ratelimiter:adaptive:metrics:" + userKey), anyDouble(), anyDouble()))
            .thenReturn(mockEvents);

        // 1. Verify Modeler calculates 50% denial rate for THIS user
        UserMetrics userMetrics = modeler.getUserMetrics(userKey);
        assertEquals(0.5, userMetrics.getDenialRate(), "User 123 should have a 50% denial rate");
        assertEquals(20.0 / 60.0, userMetrics.getCurrentRequestRate(), 0.01);

        // 2. Verify Policy Engine sees this user's need
        SystemHealth healthySystem = SystemHealth.builder().cpuUtilization(0.2).errorRate(0.0).build();
        AdaptationDecision decision = policyEngine.determineAdaptation(healthySystem, userMetrics, 100, 20);

        // 3. Verify it triggers an increase specifically because user_123 is hitting limits
        assertTrue(decision.shouldAdapt());
        assertEquals(110, decision.getRecommendedCapacity());
        assertTrue(decision.getReasoning().get("decision").contains("user is hitting limits"), 
            "Should mention user demand in reasoning");
    }
}
