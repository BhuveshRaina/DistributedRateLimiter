package dev.bnacar.distributedratelimiter.controller;

import dev.bnacar.distributedratelimiter.models.MetricsResponse;
import dev.bnacar.distributedratelimiter.monitoring.MetricsService;
import dev.bnacar.distributedratelimiter.config.SecurityConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MetricsController.class)
@Import(MetricsControllerTest.TestConfig.class)
public class MetricsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MetricsService metricsService;

    @Autowired
    private SecurityConfiguration securityConfiguration;

    @TestConfiguration
    static class TestConfig {
        @Bean
        public MetricsService metricsService() {
            return mock(MetricsService.class);
        }

        @Bean
        public SecurityConfiguration securityConfiguration() {
            return mock(SecurityConfiguration.class);
        }
    }

    private MetricsResponse metricsResponse;

    @BeforeEach
    void setUp() {
        // Mock SecurityConfiguration to avoid NullPointerExceptions
        SecurityConfiguration.Headers headers = new SecurityConfiguration.Headers();
        headers.setEnabled(true);
        when(securityConfiguration.getHeaders()).thenReturn(headers);
        when(securityConfiguration.getMaxRequestSize()).thenReturn("1MB");

        Map<String, MetricsResponse.KeyMetrics> keyMetrics = new HashMap<>();
        keyMetrics.put("user1", new MetricsResponse.KeyMetrics(5, 2, System.currentTimeMillis(), 50L));
        keyMetrics.put("user2", new MetricsResponse.KeyMetrics(3, 1, System.currentTimeMillis(), 30L));

        metricsResponse = new MetricsResponse(keyMetrics, new HashMap<>(), new java.util.ArrayList<>(), true, 8, 3, 100);
    }

    @Test
    void getMetrics_ShouldReturnMetricsResponse() throws Exception {
        when(metricsService.getMetrics()).thenReturn(metricsResponse);

        mockMvc.perform(get("/metrics"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.totalAllowedRequests").value(8))
                .andExpect(jsonPath("$.totalDeniedRequests").value(3))
                .andExpect(jsonPath("$.totalProcessingTimeMs").value(100))
                .andExpect(jsonPath("$.redisConnected").value(true))
                .andExpect(jsonPath("$.keyMetrics.user1.allowedRequests").value(5))
                .andExpect(jsonPath("$.keyMetrics.user1.deniedRequests").value(2))
                .andExpect(jsonPath("$.keyMetrics.user2.allowedRequests").value(3))
                .andExpect(jsonPath("$.keyMetrics.user2.deniedRequests").value(1))
                .andExpect(jsonPath("$.perAlgorithmMetrics").isEmpty())
                .andExpect(jsonPath("$.recentEvents").isEmpty());
    }

    @Test
    void getMetrics_WithEmptyMetrics_ShouldReturnEmptyKeyMetrics() throws Exception {
        MetricsResponse emptyResponse = new MetricsResponse(new HashMap<>(), new HashMap<>(), new java.util.ArrayList<>(), false, 0, 0, 0);
        when(metricsService.getMetrics()).thenReturn(emptyResponse);

        mockMvc.perform(get("/metrics"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.totalAllowedRequests").value(0))
                .andExpect(jsonPath("$.totalDeniedRequests").value(0))
                .andExpect(jsonPath("$.redisConnected").value(false))
                .andExpect(jsonPath("$.keyMetrics").isEmpty());
    }
}