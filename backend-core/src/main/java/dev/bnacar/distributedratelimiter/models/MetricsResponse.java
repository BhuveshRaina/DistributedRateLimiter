package dev.bnacar.distributedratelimiter.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import java.util.HashMap;

public class MetricsResponse {
            private final Map<String, KeyMetrics> keyMetrics;
            private final Map<String, AlgorithmMetrics> perAlgorithmMetrics;
            private final java.util.List<RateLimitEvent> recentEvents;
            private final boolean redisConnected;
            private final long totalAllowedRequests;
            private final long totalDeniedRequests;
            private final long totalProcessingTimeMs;
        
            @JsonCreator
            public MetricsResponse(@JsonProperty("keyMetrics") Map<String, KeyMetrics> keyMetrics,
                                  @JsonProperty("perAlgorithmMetrics") Map<String, AlgorithmMetrics> perAlgorithmMetrics,
                                  @JsonProperty("recentEvents") java.util.List<RateLimitEvent> recentEvents,
                                  @JsonProperty("redisConnected") boolean redisConnected, 
                                  @JsonProperty("totalAllowedRequests") long totalAllowedRequests, 
                                  @JsonProperty("totalDeniedRequests") long totalDeniedRequests,
                                  @JsonProperty("totalProcessingTimeMs") long totalProcessingTimeMs) {
                this.keyMetrics = keyMetrics != null ? new HashMap<>(keyMetrics) : new HashMap<>();
                this.perAlgorithmMetrics = perAlgorithmMetrics != null ? new HashMap<>(perAlgorithmMetrics) : new HashMap<>();
                this.recentEvents = recentEvents != null ? new java.util.ArrayList<>(recentEvents) : new java.util.ArrayList<>();
                this.redisConnected = redisConnected;
                this.totalAllowedRequests = totalAllowedRequests;
                this.totalDeniedRequests = totalDeniedRequests;
                this.totalProcessingTimeMs = totalProcessingTimeMs;
            }
        
            public Map<String, KeyMetrics> getKeyMetrics() {
                return new HashMap<>(keyMetrics);
            }
        
            public Map<String, AlgorithmMetrics> getPerAlgorithmMetrics() {
                return new HashMap<>(perAlgorithmMetrics);
            }
        
            public java.util.List<RateLimitEvent> getRecentEvents() {
                return new java.util.ArrayList<>(recentEvents);
            }
                public boolean isRedisConnected() {
            return redisConnected;
        }
    
        public long getTotalAllowedRequests() {
            return totalAllowedRequests;
        }
    
        public long getTotalDeniedRequests() {
            return totalDeniedRequests;
        }
    
        public long getTotalProcessingTimeMs() {
            return totalProcessingTimeMs;
        }
    
        public static class KeyMetrics {
            private final long allowedRequests;
            private final long deniedRequests;
            private final long lastAccessTime;
            private final long totalProcessingTime;
    
            @JsonCreator
            public KeyMetrics(@JsonProperty("allowedRequests") long allowedRequests, 
                             @JsonProperty("deniedRequests") long deniedRequests, 
                             @JsonProperty("lastAccessTime") long lastAccessTime,
                             @JsonProperty("totalProcessingTime") long totalProcessingTime) {
                this.allowedRequests = allowedRequests;
                this.deniedRequests = deniedRequests;
                this.lastAccessTime = lastAccessTime;
                this.totalProcessingTime = totalProcessingTime;
            }
    
            public long getAllowedRequests() {
                return allowedRequests;
            }
    
            public long getDeniedRequests() {
                return deniedRequests;
            }
    
            public long getLastAccessTime() {
                return lastAccessTime;
            }

            public long getTotalProcessingTime() {
                return totalProcessingTime;
            }
        }
    
        public static class AlgorithmMetrics {
            private final long allowedRequests;
            private final long deniedRequests;
            private final long totalProcessingTimeMs;
    
            @JsonCreator
            public AlgorithmMetrics(@JsonProperty("allowedRequests") long allowedRequests,
                                   @JsonProperty("deniedRequests") long deniedRequests,
                                   @JsonProperty("totalProcessingTimeMs") long totalProcessingTimeMs) {
                this.allowedRequests = allowedRequests;
                this.deniedRequests = deniedRequests;
                this.totalProcessingTimeMs = totalProcessingTimeMs;
            }
    
            public long getAllowedRequests() {
                return allowedRequests;
            }
    
            public long getDeniedRequests() {
                return deniedRequests;
            }
    
            public long getTotalProcessingTimeMs() {
                return totalProcessingTimeMs;
            }

            public long getTotalProcessingTime() {
                return totalProcessingTimeMs;
            }
    
            public double getSuccessRate() {
                long total = allowedRequests + deniedRequests;
                return total > 0 ? (double) allowedRequests / total * 100 : 100.0;
            }
    
                    public double getAvgResponseTimeMs() {
                        long total = allowedRequests + deniedRequests;
                        return total > 0 ? (double) totalProcessingTimeMs / total : 0.0;
                    }
                }
            
                public static class RateLimitEvent {
                    private final String id;
                    private final long timestamp;
                    private final String key;
                    private final String algorithm;
                    private final boolean allowed;
                    private final int tokens;
            
                    @JsonCreator
                    public RateLimitEvent(@JsonProperty("id") String id,
                                         @JsonProperty("timestamp") long timestamp,
                                         @JsonProperty("key") String key,
                                         @JsonProperty("algorithm") String algorithm,
                                         @JsonProperty("allowed") boolean allowed,
                                         @JsonProperty("tokens") int tokens) {
                        this.id = id;
                        this.timestamp = timestamp;
                        this.key = key;
                        this.algorithm = algorithm;
                        this.allowed = allowed;
                        this.tokens = tokens;
                    }
            
                    public String getId() { return id; }
                    public long getTimestamp() { return timestamp; }
                    public String getKey() { return key; }
                    public String getAlgorithm() { return algorithm; }
                    public boolean isAllowed() { return allowed; }
                    public int getTokens() { return tokens; }
                }
            }
            