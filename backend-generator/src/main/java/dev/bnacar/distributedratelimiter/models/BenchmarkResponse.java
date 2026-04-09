package dev.bnacar.distributedratelimiter.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response model for benchmark operations.
 */
public class BenchmarkResponse {
    
    private final boolean success;
    private final String errorMessage;
    private final long totalRequests;
    private final long successfulRequests;
    private final long errorRequests;
    private final double durationSeconds;
    private final double throughputPerSecond;
    private final double successRate;
    private final int concurrentThreads;
    private final long requestsPerThread;
    
    // Response time statistics (ms)
    private final double avgResponseTimeMs;
    private final double p50ResponseTimeMs;
    private final double p95ResponseTimeMs;
    private final double p99ResponseTimeMs;
    
    // Constructor for successful benchmark
    @JsonCreator
    public BenchmarkResponse(
            @JsonProperty("totalRequests") long totalRequests, 
            @JsonProperty("successfulRequests") long successfulRequests, 
            @JsonProperty("errorRequests") long errorRequests,
            @JsonProperty("durationSeconds") double durationSeconds, 
            @JsonProperty("throughputPerSecond") double throughputPerSecond, 
            @JsonProperty("successRate") double successRate,
            @JsonProperty("concurrentThreads") int concurrentThreads, 
            @JsonProperty("requestsPerThread") long requestsPerThread,
            @JsonProperty("avgResponseTimeMs") double avgResponseTimeMs,
            @JsonProperty("p50ResponseTimeMs") double p50ResponseTimeMs,
            @JsonProperty("p95ResponseTimeMs") double p95ResponseTimeMs,
            @JsonProperty("p99ResponseTimeMs") double p99ResponseTimeMs) {
        this.success = true;
        this.errorMessage = null;
        this.totalRequests = totalRequests;
        this.successfulRequests = successfulRequests;
        this.errorRequests = errorRequests;
        this.durationSeconds = durationSeconds;
        this.throughputPerSecond = throughputPerSecond;
        this.successRate = successRate;
        this.concurrentThreads = concurrentThreads;
        this.requestsPerThread = requestsPerThread;
        this.avgResponseTimeMs = avgResponseTimeMs;
        this.p50ResponseTimeMs = p50ResponseTimeMs;
        this.p95ResponseTimeMs = p95ResponseTimeMs;
        this.p99ResponseTimeMs = p99ResponseTimeMs;
    }
    
    // Constructor for error response
    private BenchmarkResponse(String errorMessage) {
        this.success = false;
        this.errorMessage = errorMessage;
        this.totalRequests = 0;
        this.successfulRequests = 0;
        this.errorRequests = 0;
        this.durationSeconds = 0;
        this.throughputPerSecond = 0;
        this.successRate = 0;
        this.concurrentThreads = 0;
        this.requestsPerThread = 0;
        this.avgResponseTimeMs = 0;
        this.p50ResponseTimeMs = 0;
        this.p95ResponseTimeMs = 0;
        this.p99ResponseTimeMs = 0;
    }
    
    public static BenchmarkResponse error(String errorMessage) {
        return new BenchmarkResponse(errorMessage);
    }
    
    public boolean isSuccess() {
        return success;
    }
    
    public String getErrorMessage() {
        return errorMessage;
    }
    
    public long getTotalRequests() {
        return totalRequests;
    }
    
    public long getSuccessfulRequests() {
        return successfulRequests;
    }
    
    public long getErrorRequests() {
        return errorRequests;
    }
    
    public double getDurationSeconds() {
        return durationSeconds;
    }
    
    public double getThroughputPerSecond() {
        return throughputPerSecond;
    }
    
    public double getSuccessRate() {
        return successRate;
    }
    
    public int getConcurrentThreads() {
        return concurrentThreads;
    }
    
    public long getRequestsPerThread() {
        return requestsPerThread;
    }

    public double getAvgResponseTimeMs() {
        return avgResponseTimeMs;
    }

    public double getP50ResponseTimeMs() {
        return p50ResponseTimeMs;
    }

    public double getP95ResponseTimeMs() {
        return p95ResponseTimeMs;
    }

    public double getP99ResponseTimeMs() {
        return p99ResponseTimeMs;
    }
    
    /**
     * Check if the benchmark meets the performance target.
     */
    public boolean meetsPerformanceTarget(double targetThroughput) {
        return success && throughputPerSecond >= targetThroughput;
    }
    
    @Override
    public String toString() {
        if (!success) {
            return "BenchmarkResponse{success=false, error='" + errorMessage + "'}";
        }
        
        return String.format(
            "BenchmarkResponse{success=%s, totalRequests=%d, successfulRequests=%d, " +
            "errorRequests=%d, durationSeconds=%.2f, throughputPerSecond=%.2f, " +
            "successRate=%.2f%%, concurrentThreads=%d, requestsPerThread=%d}",
            success, totalRequests, successfulRequests, errorRequests, 
            durationSeconds, throughputPerSecond, successRate, concurrentThreads, requestsPerThread
        );
    }
}