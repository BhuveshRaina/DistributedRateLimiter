package dev.bnacar.distributedratelimiter.controller;

import dev.bnacar.distributedratelimiter.models.BenchmarkRequest;
import dev.bnacar.distributedratelimiter.models.BenchmarkResponse;
import dev.bnacar.distributedratelimiter.ratelimit.RateLimiterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.CrossOrigin;

import jakarta.validation.Valid;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Benchmark controller for measuring rate limiter performance.
 * Provides endpoints to test throughput under various load conditions.
 */
@RestController
@RequestMapping("/api/benchmark")
@Tag(name = "benchmark-controller", description = "Performance benchmarking and load testing utilities")
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:3000", "http://127.0.0.1:5173", "http://127.0.0.1:3000", "http://[::1]:5173", "http://[::1]:3000"})
public class BenchmarkController {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(BenchmarkController.class);
    private final dev.bnacar.distributedratelimiter.ratelimit.RateLimiterService rateLimiterService;
    private final ExecutorService benchmarkExecutor;

    public BenchmarkController(@org.springframework.beans.factory.annotation.Qualifier("distributedRateLimiterService") 
                             dev.bnacar.distributedratelimiter.ratelimit.RateLimiterService rateLimiterService) {
        this.rateLimiterService = rateLimiterService;
        this.benchmarkExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "Benchmark-Worker");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Run a performance benchmark of the rate limiter.
     * Tests throughput under specified load conditions.
     */
    @PostMapping("/run")
    @Operation(summary = "Run performance benchmark",
               description = "Executes a performance benchmark with configurable load parameters to measure rate limiter throughput and latency")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", 
                    description = "Benchmark completed successfully",
                    content = @Content(mediaType = "application/json",
                                     schema = @Schema(implementation = BenchmarkResponse.class),
                                     examples = @ExampleObject(value = "{\"totalRequests\":1000,\"successCount\":850,\"errorCount\":0,\"durationSeconds\":10.5,\"throughputPerSecond\":95.2,\"successRate\":85.0,\"concurrentThreads\":10,\"requestsPerThread\":100}"))),
        @ApiResponse(responseCode = "400", 
                    description = "Benchmark configuration invalid or benchmark failed")
    })
    public ResponseEntity<BenchmarkResponse> runBenchmark(
            @Parameter(description = "Benchmark configuration parameters", required = true,
                      content = @Content(examples = @ExampleObject(value = "{\"concurrentThreads\":10,\"requestsPerThread\":100,\"durationSeconds\":30,\"keyPrefix\":\"benchmark\",\"tokensPerRequest\":1,\"delayBetweenRequestsMs\":0}")))
            @Valid @RequestBody BenchmarkRequest request) {
        logger.info("Starting benchmark: threads={}, reqPerThread={}, duration={}s", 
                request.getConcurrentThreads(), request.getRequestsPerThread(), request.getDurationSeconds());
        
        long startTime = System.nanoTime();
        
        AtomicLong successCount = new AtomicLong(0);
        AtomicLong errorCount = new AtomicLong(0);
        AtomicLong totalRequests = new AtomicLong(0);
        java.util.concurrent.ConcurrentLinkedQueue<Long> responseTimes = new java.util.concurrent.ConcurrentLinkedQueue<>();
        
        CountDownLatch latch = new CountDownLatch(request.getConcurrentThreads());
        
        // Launch concurrent workers
        for (int i = 0; i < request.getConcurrentThreads(); i++) {
            final int threadId = i + 1;
            benchmarkExecutor.submit(() -> {
                try {
                    runWorkerThread(request, threadId, successCount, errorCount, totalRequests, responseTimes);
                } finally {
                    latch.countDown();
                }
            });
        }
        
        try {
            // Wait for all workers to complete or timeout
            boolean completed = latch.await(request.getDurationSeconds() + 10, TimeUnit.SECONDS);
            if (!completed) {
                logger.warn("Benchmark timed out");
                return ResponseEntity.badRequest().body(
                    BenchmarkResponse.error("Benchmark timed out")
                );
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Benchmark interrupted", e);
            return ResponseEntity.badRequest().body(
                BenchmarkResponse.error("Benchmark interrupted")
            );
        }
        
        long endTime = System.nanoTime();
        double durationSeconds = (endTime - startTime) / 1_000_000_000.0;
        
        // If duration is effectively 0, avoid division by zero
        if (durationSeconds < 0.001) {
            durationSeconds = 0.001;
        }
        
        long total = totalRequests.get();
        long success = successCount.get();
        long errors = errorCount.get();
        
        logger.info("Benchmark results: total={}, success={}, errors={}, duration={}s, responseTimesCollected={}", 
                total, success, errors, durationSeconds, responseTimes.size());
        
        double throughputPerSecond = total / durationSeconds;
        double successRate = total > 0 ? (double) success / total * 100.0 : 0.0;
        
        // Calculate latency stats
        double avgLatency = 0;
        double p50 = 0;
        double p95 = 0;
        double p99 = 0;
        
        if (!responseTimes.isEmpty()) {
            java.util.List<Long> times = new java.util.ArrayList<>(responseTimes);
            java.util.Collections.sort(times);
            
            avgLatency = times.stream().mapToLong(Long::longValue).average().orElse(0.0) / 1_000_000.0;
            p50 = times.get((int) (times.size() * 0.50)) / 1_000_000.0;
            p95 = times.get((int) (times.size() * 0.95)) / 1_000_000.0;
            p99 = times.get((int) (times.size() * 0.99)) / 1_000_000.0;
        }
        
        BenchmarkResponse response = new BenchmarkResponse(
            total,
            success,
            errors,
            durationSeconds,
            throughputPerSecond,
            successRate,
            request.getConcurrentThreads(),
            request.getRequestsPerThread(),
            avgLatency,
            p50,
            p95,
            p99
        );
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * Simple health check endpoint for the benchmark service.
     */
    @GetMapping("/health")
    @Operation(summary = "Benchmark service health check",
               description = "Check if the benchmark service is operational")
    @ApiResponse(responseCode = "200", 
                description = "Benchmark service is healthy")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Benchmark service is healthy");
    }
    
    private void runWorkerThread(BenchmarkRequest request, int threadId, 
                                AtomicLong successCount, AtomicLong errorCount, 
                                AtomicLong totalRequests,
                                java.util.concurrent.ConcurrentLinkedQueue<Long> responseTimes) {
        long requestsPerThread = request.getRequestsPerThread();
        String keyPrefix = request.getKeyPrefix() != null ? request.getKeyPrefix() : "benchmark";
        String key = keyPrefix + ":" + threadId;
        int tokensPerRequest = request.getTokensPerRequest();
        
        long startNano = System.nanoTime();
        long durationNano = (long) request.getDurationSeconds() * 1_000_000_000L;
        
        long currentRequestCount = 0;
        
        // Resolve algorithm override if provided
        dev.bnacar.distributedratelimiter.ratelimit.RateLimitAlgorithm algorithm = null;
        if (request.getAlgorithmOverride() != null && !request.getAlgorithmOverride().equals("none")) {
            try {
                algorithm = dev.bnacar.distributedratelimiter.ratelimit.RateLimitAlgorithm.valueOf(
                    request.getAlgorithmOverride().toUpperCase().replace('-', '_')
                );
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid algorithm override: {}", request.getAlgorithmOverride());
            }
        }

        while (currentRequestCount < requestsPerThread) {
            // Check if we've exceeded the duration
            if (System.nanoTime() - startNano > durationNano) {
                break;
            }
            
            long requestStart = System.nanoTime();
            try {
                // In a real load test, custom headers would be added to the HTTP request
                // For this internal benchmark, we log them if present
                if (request.getCustomHeaders() != null && !request.getCustomHeaders().isEmpty()) {
                    // Simulation of header processing
                    logger.trace("Processing custom headers for request {}: {}", currentRequestCount, request.getCustomHeaders());
                }

                boolean allowed = rateLimiterService.isAllowed(key, tokensPerRequest, algorithm);
                long requestDurationNano = System.nanoTime() - requestStart;
                
                // Simulate request timeout
                if (request.getTimeoutMs() != null && (requestDurationNano / 1_000_000) > request.getTimeoutMs()) {
                    throw new java.util.concurrent.TimeoutException("Request timed out");
                }

                responseTimes.add(requestDurationNano);
                
                totalRequests.incrementAndGet();
                currentRequestCount++;
                
                if (allowed) {
                    successCount.incrementAndGet();
                }
                
                // Optional delay between requests
                if (request.getDelayBetweenRequestsMs() != null && request.getDelayBetweenRequestsMs() > 0) {
                    Thread.sleep(request.getDelayBetweenRequestsMs());
                }
                
            } catch (Exception e) {
                long requestDurationNano = System.nanoTime() - requestStart;
                responseTimes.add(requestDurationNano);
                
                totalRequests.incrementAndGet();
                errorCount.incrementAndGet();
                currentRequestCount++;
            }
        }
    }
}