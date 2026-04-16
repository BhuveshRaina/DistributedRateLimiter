package dev.bnacar.distributedratelimiter.controller;

import dev.bnacar.distributedratelimiter.models.ConfigurationResponse;
import dev.bnacar.distributedratelimiter.models.ConfigurationStats;
import dev.bnacar.distributedratelimiter.models.DefaultConfigRequest;
import dev.bnacar.distributedratelimiter.ratelimit.ConfigurationResolver;
import dev.bnacar.distributedratelimiter.ratelimit.RateLimiterConfiguration;
import dev.bnacar.distributedratelimiter.ratelimit.RateLimiterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.CrossOrigin;

/**
 * Controller for managing rate limiter configuration.
 */
@RestController
@RequestMapping("/api/ratelimit/config")
@Tag(name = "Rate Limit Configuration", description = "Configuration management for rate limiter settings")
@CrossOrigin(origins = "*")
public class RateLimitConfigController {

    private final RateLimiterConfiguration configuration;
    private final ConfigurationResolver configurationResolver;
    private final RateLimiterService rateLimiterService;
    private final dev.bnacar.distributedratelimiter.adaptive.AdaptiveRateLimitEngine adaptiveEngine;

    public RateLimitConfigController(RateLimiterConfiguration configuration,
                                   ConfigurationResolver configurationResolver,
                                   RateLimiterService rateLimiterService,
                                   dev.bnacar.distributedratelimiter.adaptive.AdaptiveRateLimitEngine adaptiveEngine) {
        this.configuration = configuration;
        this.configurationResolver = configurationResolver;
        this.rateLimiterService = rateLimiterService;
        this.adaptiveEngine = adaptiveEngine;
    }

    /**
     * Get the current configuration.
     */
    @GetMapping
    @Operation(summary = "Get current rate limiter configuration",
               description = "Retrieves the current configuration including default settings, per-key configurations, and patterns")
    @ApiResponse(responseCode = "200", 
                description = "Current configuration retrieved successfully",
                content = @Content(mediaType = "application/json",
                                 schema = @Schema(implementation = ConfigurationResponse.class)))
    public ResponseEntity<ConfigurationResponse> getConfiguration() {
        configurationResolver.clearCache();
        java.util.Map<String, RateLimiterConfiguration.KeyConfig> effectiveConfigs = new java.util.HashMap<>();
        
        // Resolve effective limits for all configured keys and patterns
        java.util.Set<String> allKeys = configurationResolver.getValidConfigKeys();
        for (String key : allKeys) {
            dev.bnacar.distributedratelimiter.ratelimit.RateLimitConfig resolved = configurationResolver.resolveConfig(key);
            RateLimiterConfiguration.KeyConfig kc = new RateLimiterConfiguration.KeyConfig();
            kc.setCapacity(resolved.getCapacity());
            kc.setRefillRate(resolved.getRefillRate());
            kc.setAlgorithm(resolved.getAlgorithm());
            kc.setAdaptiveEnabled(resolved.isAdaptiveEnabled());
            kc.setCleanupIntervalMs(resolved.getCleanupIntervalMs());
            effectiveConfigs.put(key, kc);
        }

        ConfigurationResponse response = new ConfigurationResponse(
            configuration.getCapacity(),
            configuration.getRefillRate(),
            configuration.getCleanupIntervalMs(),
            configuration.getKeys(),
            configuration.getPatterns(),
            effectiveConfigs
        );
        return ResponseEntity.ok(response);
    }

    /**
     * Update per-key configuration.
     */
    @PostMapping("/keys/{key}")
    public ResponseEntity<String> updateKeyConfiguration(@PathVariable("key") String key,
                                                        @RequestBody RateLimiterConfiguration.KeyConfig keyConfig) {
        configuration.putKey(key, keyConfig);
        reloadConfiguration();
        return ResponseEntity.ok("Configuration updated for key: " + key);
    }

    /**
     * Update pattern configuration.
     */
    @PostMapping("/patterns/{pattern}")
    public ResponseEntity<String> updatePatternConfiguration(@PathVariable("pattern") String pattern,
                                                            @RequestBody RateLimiterConfiguration.KeyConfig keyConfig) {
        configuration.putPattern(pattern, keyConfig);
        reloadConfiguration();
        return ResponseEntity.ok("Configuration updated for pattern: " + pattern);
    }

    /**
     * Update default configuration.
     */
    @PostMapping("/default")
    public ResponseEntity<String> updateDefaultConfiguration(@RequestBody DefaultConfigRequest request) {
        if (request.getCapacity() != null) {
            configuration.setCapacity(request.getCapacity());
        }
        if (request.getRefillRate() != null) {
            configuration.setRefillRate(request.getRefillRate());
        }
        if (request.getCleanupIntervalMs() != null) {
            configuration.setCleanupIntervalMs(request.getCleanupIntervalMs());
        }
        reloadConfiguration();
        return ResponseEntity.ok("Default configuration updated");
    }

    /**
     * Remove per-key configuration.
     */
    @DeleteMapping("/keys/{key}")
    public ResponseEntity<String> removeKeyConfiguration(@PathVariable("key") String key) {
        configuration.removeKey(key);
        adaptiveEngine.removeAdaptiveStatus(key); // IMMEDIATE cleanup from adaptive page
        reloadConfiguration();
        return ResponseEntity.ok("Configuration removed for key: " + key);
    }

    /**
     * Remove pattern configuration.
     */
    @DeleteMapping("/patterns/{pattern}")
    public ResponseEntity<String> removePatternConfiguration(@PathVariable("pattern") String pattern) {
        configuration.removePattern(pattern);
        adaptiveEngine.removeAdaptiveStatus(pattern); // IMMEDIATE cleanup from adaptive page
        reloadConfiguration();
        return ResponseEntity.ok("Configuration removed for pattern: " + pattern);
    }

    /**
     * Reload configuration - clears caches and buckets.
     */
    @PostMapping("/reload")
    public ResponseEntity<String> reloadConfiguration() {
        configurationResolver.clearCache();
        rateLimiterService.clearBuckets();
        return ResponseEntity.ok("Configuration reloaded successfully");
    }

    /**
     * Get configuration statistics.
     */
    @GetMapping("/stats")
    public ResponseEntity<ConfigurationStats> getConfigurationStats() {
        ConfigurationStats stats = new ConfigurationStats(
            configurationResolver.getCacheSize(),
            configuration.getKeys().size(),
            configuration.getKeys().size(),
            configuration.getPatterns().size()
        );
        return ResponseEntity.ok(stats);
    }

}