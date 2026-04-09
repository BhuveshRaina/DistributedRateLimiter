package dev.bnacar.distributedratelimiter.controller;

import dev.bnacar.distributedratelimiter.adaptive.AdaptiveRateLimitEngine;
import dev.bnacar.distributedratelimiter.models.AdaptiveInfo;
import dev.bnacar.distributedratelimiter.models.RateLimitRequest;
import dev.bnacar.distributedratelimiter.models.RateLimitResponse;
import dev.bnacar.distributedratelimiter.ratelimit.RateLimiter;
import dev.bnacar.distributedratelimiter.ratelimit.RateLimiterService;
import dev.bnacar.distributedratelimiter.ratelimit.RateLimitConfig;
import dev.bnacar.distributedratelimiter.ratelimit.RateLimitAlgorithm;
import dev.bnacar.distributedratelimiter.ratelimit.ConfigurationResolver;
import dev.bnacar.distributedratelimiter.security.ApiKeyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/ratelimit")
@Tag(name = "Rate Limit", description = "Rate limiting operations")
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:3000", "http://127.0.0.1:5173", "http://127.0.0.1:3000", "http://[::1]:5173", "http://[::1]:3000"})
public class RateLimitController {
    private static final Logger logger = LoggerFactory.getLogger(RateLimitController.class);

    private final RateLimiterService rateLimiterService;
    private final ApiKeyService apiKeyService;
    private final AdaptiveRateLimitEngine adaptiveEngine;
    private final ConfigurationResolver configurationResolver;
    
    @Value("${ratelimiter.adaptive.enabled:true}")
    private boolean adaptiveRateLimitingEnabled;

    @Value("${ratelimiter.strict-mode:false}")
    private boolean strictMode;

    public RateLimitController(RateLimiterService rateLimiterService,
                              ApiKeyService apiKeyService,
                              AdaptiveRateLimitEngine adaptiveEngine,
                              ConfigurationResolver configurationResolver) {
        this.rateLimiterService = rateLimiterService;
        this.apiKeyService = apiKeyService;
        this.adaptiveEngine = adaptiveEngine;
        this.configurationResolver = configurationResolver;
    }

    @PostMapping("/check")
    @Operation(summary = "Check rate limit for a key",
               description = "Checks if a request is allowed based on the configured rate limits for the given key.")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", 
                    description = "Request allowed",
                    content = @Content(mediaType = "application/json",
                                     schema = @Schema(implementation = RateLimitResponse.class),
                                     examples = @ExampleObject(value = "{\"key\":\"user:123\",\"tokensRequested\":1,\"allowed\":true}"))),
        @ApiResponse(responseCode = "429", 
                    description = "Rate limit exceeded",
                    content = @Content(mediaType = "application/json",
                                     schema = @Schema(implementation = RateLimitResponse.class),
                                     examples = @ExampleObject(value = "{\"key\":\"user:123\",\"tokensRequested\":1,\"allowed\":false}"))),
        @ApiResponse(responseCode = "401", 
                    description = "Invalid API key",
                    content = @Content(mediaType = "application/json",
                                     schema = @Schema(implementation = RateLimitResponse.class))),
        @ApiResponse(responseCode = "403", 
                    description = "Request forbidden",
                    content = @Content(mediaType = "application/json",
                                     schema = @Schema(implementation = RateLimitResponse.class)))
    })
    public ResponseEntity<RateLimitResponse> checkRateLimit(
            @Parameter(description = "Rate limit request containing key, tokens, and optional API key",
                      required = true,
                      content = @Content(examples = @ExampleObject(value = "{\"key\":\"user:123\",\"tokens\":1,\"apiKey\":\"your-api-key\"}")))
            @Valid @RequestBody RateLimitRequest request,
            HttpServletRequest httpRequest) {
        
        // Validate API key if provided or required
        if (!apiKeyService.isValidApiKey(request.getApiKey())) {
            RateLimitResponse response = new RateLimitResponse(request.getKey(), request.getTokens(), false);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
        }
        
        // Use the raw key provided in the request
        String effectiveKey = request.getKey();
        
        // Standard single-algorithm rate limiting
        RateLimiter.ConsumptionResult result = rateLimiterService.isAllowedWithResult(effectiveKey, request.getTokens(), request.getAlgorithm());
        boolean allowed = result.allowed;
        
        // Record traffic event for adaptive learning
        if (adaptiveRateLimitingEnabled) {
            adaptiveEngine.recordTrafficEvent(effectiveKey, request.getTokens(), allowed);
        }
        
        // Build adaptive info if enabled
        AdaptiveInfo adaptiveInfo = null;
        if (adaptiveRateLimitingEnabled) {
            adaptiveInfo = buildAdaptiveInfo(effectiveKey);
        }
        
        RateLimitResponse response = new RateLimitResponse(request.getKey(), request.getTokens(), allowed, result.remainingTokens, result.capacity, adaptiveInfo);
        
        // Record the algorithm actually used for the response
        RateLimitAlgorithm usedAlgorithm = request.getAlgorithm();
        if (usedAlgorithm == null) {
            RateLimitConfig config = rateLimiterService.getKeyConfiguration(effectiveKey);
            usedAlgorithm = config != null ? config.getAlgorithm() : RateLimitAlgorithm.TOKEN_BUCKET;
        }
        response.setAlgorithm(usedAlgorithm.name());
        
        if (allowed) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(response);
        }
    }
    
    /**
     * Build adaptive info for the response if adaptive rate limiting is enabled
     */
    private AdaptiveInfo buildAdaptiveInfo(String key) {
        try {
            AdaptiveRateLimitEngine.AdaptedLimits adaptedLimits = adaptiveEngine.getAdaptedLimits(key);
            
            if (adaptedLimits != null) {
                AdaptiveInfo.OriginalLimits originalLimits = new AdaptiveInfo.OriginalLimits(
                    adaptedLimits.originalCapacity,
                    adaptedLimits.originalRefillRate
                );
                
                AdaptiveInfo.CurrentLimits currentLimits = new AdaptiveInfo.CurrentLimits(
                    adaptedLimits.adaptedCapacity,
                    adaptedLimits.adaptedRefillRate
                );
                
                String reasoning = adaptedLimits.reasoning.getOrDefault("decision", "Adaptive adjustment applied");
                String nextEvaluation = "PT5M"; // 5 minutes default
                
                return new AdaptiveInfo(
                    originalLimits,
                    currentLimits,
                    reasoning,
                    adaptedLimits.timestamp,
                    nextEvaluation
                );
            }
        } catch (Exception e) {
            logger.debug("Could not build adaptive info for key: {}", key, e);
        }
        return null;
    }
}