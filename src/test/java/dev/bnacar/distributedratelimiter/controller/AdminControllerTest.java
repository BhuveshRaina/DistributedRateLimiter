package dev.bnacar.distributedratelimiter.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bnacar.distributedratelimiter.models.AdminLimitRequest;
import dev.bnacar.distributedratelimiter.ratelimit.ConfigurationResolver;
import dev.bnacar.distributedratelimiter.ratelimit.RateLimitConfig;
import dev.bnacar.distributedratelimiter.ratelimit.RateLimiterConfiguration;
import dev.bnacar.distributedratelimiter.ratelimit.RateLimiterService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminController.class)
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RateLimiterService rateLimiterService;

    @Autowired
    private RateLimiterConfiguration configuration;

    @Autowired
    private ObjectMapper objectMapper;

    @TestConfiguration
    static class TestConfig {
        @Bean
        public RateLimiterService rateLimiterService() { return mock(RateLimiterService.class); }
        @Bean
        public RateLimiterConfiguration configuration() { 
            RateLimiterConfiguration mock = mock(RateLimiterConfiguration.class);
            when(mock.getKeys()).thenReturn(new HashMap<>());
            return mock; 
        }
        @Bean
        public ConfigurationResolver configurationResolver() { return mock(ConfigurationResolver.class); }
        @Bean
        public dev.bnacar.distributedratelimiter.config.SecurityConfiguration securityConfiguration() {
            dev.bnacar.distributedratelimiter.config.SecurityConfiguration config = mock(dev.bnacar.distributedratelimiter.config.SecurityConfiguration.class);
            when(config.getHeaders()).thenReturn(new dev.bnacar.distributedratelimiter.config.SecurityConfiguration.Headers());
            return config;
        }
    }

    private static final String AUTH_HEADER = "Basic " + java.util.Base64.getEncoder().encodeToString("admin:admin123".getBytes());

    @Test
    void testGetKeyLimits_Found() throws Exception {
        String key = "test-key";
        RateLimitConfig config = new RateLimitConfig(100, 10);
        when(rateLimiterService.getKeyConfiguration(key)).thenReturn(config);

        mockMvc.perform(get("/admin/limits/" + key)
                .header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacity").value(100))
                .andExpect(jsonPath("$.refillRate").value(10));
    }

    @Test
    void testGetKeyLimits_NotFound() throws Exception {
        when(rateLimiterService.getKeyConfiguration(anyString())).thenReturn(null);

        mockMvc.perform(get("/admin/limits/non-existent")
                .header("Authorization", AUTH_HEADER))
                .andExpect(status().isNotFound());
    }

    @Test
    void testUpdateKeyLimits() throws Exception {
        String key = "new-key";
        AdminLimitRequest request = new AdminLimitRequest();
        request.setCapacity(50);
        request.setRefillRate(5);
        
        RateLimitConfig updatedConfig = new RateLimitConfig(50, 5);
        when(rateLimiterService.getKeyConfiguration(key)).thenReturn(updatedConfig);

        mockMvc.perform(put("/admin/limits/" + key)
                .header("Authorization", AUTH_HEADER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacity").value(50));
        
        verify(configuration).putKey(eq(key), any());
        verify(rateLimiterService).removeKey(key);
    }

    @Test
    void testRemoveKeyLimits() throws Exception {
        String key = "delete-me";
        when(configuration.removeKey(key)).thenReturn(new RateLimiterConfiguration.KeyConfig());

        mockMvc.perform(delete("/admin/limits/" + key)
                .header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk());
        
        verify(configuration).removeKey(key);
        verify(rateLimiterService).removeKey(key);
    }
}
