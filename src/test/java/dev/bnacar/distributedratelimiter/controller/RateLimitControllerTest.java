package dev.bnacar.distributedratelimiter.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bnacar.distributedratelimiter.adaptive.AdaptiveRateLimitEngine;
import dev.bnacar.distributedratelimiter.models.RateLimitRequest;
import dev.bnacar.distributedratelimiter.ratelimit.ConfigurationResolver;
import dev.bnacar.distributedratelimiter.ratelimit.RateLimiter;
import dev.bnacar.distributedratelimiter.ratelimit.RateLimiterService;
import dev.bnacar.distributedratelimiter.security.ApiKeyService;
import dev.bnacar.distributedratelimiter.security.IpAddressExtractor;
import dev.bnacar.distributedratelimiter.security.IpSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RateLimitController.class)
class RateLimitControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RateLimiterService rateLimiterService;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private IpSecurityService ipSecurityService;

    @Autowired
    private IpAddressExtractor ipAddressExtractor;

    @Autowired
    private AdaptiveRateLimitEngine adaptiveEngine;

    @Autowired
    private ObjectMapper objectMapper;

    @TestConfiguration
    static class TestConfig {
        @Bean
        public RateLimiterService rateLimiterService() { return mock(RateLimiterService.class); }
        @Bean
        public ApiKeyService apiKeyService() { return mock(ApiKeyService.class); }
        @Bean
        public IpSecurityService ipSecurityService() { return mock(IpSecurityService.class); }
        @Bean
        public IpAddressExtractor ipAddressExtractor() { return mock(IpAddressExtractor.class); }
        @Bean
        public AdaptiveRateLimitEngine adaptiveEngine() { return mock(AdaptiveRateLimitEngine.class); }
        @Bean
        public ConfigurationResolver configurationResolver() { return mock(ConfigurationResolver.class); }
        @Bean
        public dev.bnacar.distributedratelimiter.config.SecurityConfiguration securityConfiguration() {
            dev.bnacar.distributedratelimiter.config.SecurityConfiguration config = mock(dev.bnacar.distributedratelimiter.config.SecurityConfiguration.class);
            when(config.getHeaders()).thenReturn(new dev.bnacar.distributedratelimiter.config.SecurityConfiguration.Headers());
            return config;
        }
    }

    @BeforeEach
    void setUp() {
        when(ipAddressExtractor.getClientIpAddress(any())).thenReturn("127.0.0.1");
        when(ipSecurityService.isIpAllowed(anyString())).thenReturn(true);
        when(apiKeyService.isValidApiKey(any())).thenReturn(true);
    }

    @Test
    void testCheckRateLimit_Allowed() throws Exception {
        RateLimitRequest request = new RateLimitRequest("user1", 1);
        RateLimiter.ConsumptionResult result = new RateLimiter.ConsumptionResult(true, 9, 10);
        
        when(rateLimiterService.isAllowedWithResult(eq("user1"), eq(1), any())).thenReturn(result);

        mockMvc.perform(post("/api/ratelimit/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.remainingTokens").value(9));
    }

    @Test
    void testCheckRateLimit_Denied() throws Exception {
        RateLimitRequest request = new RateLimitRequest("user1", 1);
        RateLimiter.ConsumptionResult result = new RateLimiter.ConsumptionResult(false, 0, 10);
        
        when(rateLimiterService.isAllowedWithResult(eq("user1"), eq(1), any())).thenReturn(result);

        mockMvc.perform(post("/api/ratelimit/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.remainingTokens").value(0));
    }

    @Test
    void testCheckRateLimit_InvalidApiKey() throws Exception {
        RateLimitRequest request = new RateLimitRequest("user1", 1, "bad-key");
        when(apiKeyService.isValidApiKey("bad-key")).thenReturn(false);

        mockMvc.perform(post("/api/ratelimit/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }
}
