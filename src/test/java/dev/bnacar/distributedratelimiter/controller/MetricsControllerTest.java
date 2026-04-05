package dev.bnacar.distributedratelimiter.controller;

import dev.bnacar.distributedratelimiter.models.MetricsResponse;
import dev.bnacar.distributedratelimiter.monitoring.MetricsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.HashMap;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MetricsController.class)
class MetricsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MetricsService metricsService;

    @TestConfiguration
    static class TestConfig {
        @Bean
        public MetricsService metricsService() {
            return mock(MetricsService.class);
        }
        
        @Bean
        public dev.bnacar.distributedratelimiter.config.SecurityConfiguration securityConfiguration() {
            dev.bnacar.distributedratelimiter.config.SecurityConfiguration config = mock(dev.bnacar.distributedratelimiter.config.SecurityConfiguration.class);
            when(config.getHeaders()).thenReturn(new dev.bnacar.distributedratelimiter.config.SecurityConfiguration.Headers());
            return config;
        }
    }

    @Test
    void testGetMetrics_ReturnsCorrectData() throws Exception {
        MetricsResponse mockResponse = new MetricsResponse(
            new HashMap<>(), 
            new HashMap<>(), 
            Collections.emptyList(), 
            true, 
            100L, 
            5L, 
            500L
        );
        
        when(metricsService.getMetrics()).thenReturn(mockResponse);

        mockMvc.perform(get("/metrics"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.totalAllowedRequests").value(100))
                .andExpect(jsonPath("$.totalDeniedRequests").value(5))
                .andExpect(jsonPath("$.redisConnected").value(true));
    }
}
