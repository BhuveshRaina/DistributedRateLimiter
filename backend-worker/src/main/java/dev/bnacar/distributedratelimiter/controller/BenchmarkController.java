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
import org.springframework.web.client.HttpClientErrorException;

import jakarta.validation.Valid;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api/benchmark")
@Tag(name = "benchmark-controller", description = "Performance benchmarking and load testing utilities")
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:3000"})
public class BenchmarkController {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(BenchmarkController.class);
    private final dev.bnacar.distributedratelimiter.ratelimit.RateLimiterService rateLimiterService;
    private final dev.bnacar.distributedratelimiter.monitoring.MetricsService metricsService;
    private final ExecutorService benchmarkExecutor;
    private final org.springframework.web.client.RestTemplate restTemplate;

    @org.springframework.beans.factory.annotation.Value("${ratelimiter.loadbalancer.url:}")
    private String loadBalancerUrl;

    public BenchmarkController(dev.bnacar.distributedratelimiter.ratelimit.RateLimiterService rateLimiterService,
                             dev.bnacar.distributedratelimiter.monitoring.MetricsService metricsService) {
        this.rateLimiterService = rateLimiterService;
        this.metricsService = metricsService;
        
        org.springframework.http.client.SimpleClientHttpRequestFactory factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(5000);
        this.restTemplate = new org.springframework.web.client.RestTemplate(factory);
        
        this.benchmarkExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "Benchmark-Worker");
            t.setDaemon(true);
            return t;
        });
    }

    @PostMapping("/run")
    public ResponseEntity<BenchmarkResponse> runBenchmark(@Valid @RequestBody BenchmarkRequest request) {
        String prefix = request.getKeyPrefix() != null ? request.getKeyPrefix() : "benchmark";
        
        metricsService.clearMetricsByPrefix(prefix);
        // Removed artificial delay
        
        long startTime = System.nanoTime();
        AtomicLong successCount = new AtomicLong(0);
        AtomicLong errorCount = new AtomicLong(0);
        AtomicLong totalRequests = new AtomicLong(0);
        java.util.concurrent.ConcurrentLinkedQueue<Long> responseTimes = new java.util.concurrent.ConcurrentLinkedQueue<>();
        
        CountDownLatch latch = new CountDownLatch(request.getConcurrentThreads());
        
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
            latch.await(request.getDurationSeconds() + 5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        long endTime = System.nanoTime();
        double durationSeconds = (endTime - startTime) / 1_000_000_000.0;
        if (durationSeconds < 0.001) durationSeconds = 0.001;
        
        long total = totalRequests.get();
        long success = successCount.get();
        
        double throughputPerSecond = total / durationSeconds;
        double successRate = total > 0 ? (double) success / total * 100.0 : 0.0;
        
        double avgLatency = 0, p50 = 0, p95 = 0, p99 = 0;
        if (!responseTimes.isEmpty()) {
            java.util.List<Long> times = new java.util.ArrayList<>(responseTimes);
            java.util.Collections.sort(times);
            avgLatency = times.stream().mapToLong(Long::longValue).average().orElse(0.0) / 1_000_000.0;
            p50 = times.get((int) (times.size() * 0.50)) / 1_000_000.0;
            p95 = times.get((int) (times.size() * 0.95)) / 1_000_000.0;
            p99 = times.get((int) (times.size() * 0.99)) / 1_000_000.0;
        }
        
        return ResponseEntity.ok(new BenchmarkResponse(total, success, errorCount.get(), durationSeconds, throughputPerSecond, successRate, request.getConcurrentThreads(), request.getRequestsPerThread(), avgLatency, p50, p95, p99));
    }
    
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Benchmark service is healthy");
    }

    private void runWorkerThread(BenchmarkRequest request, int threadId, 
                                AtomicLong successCount, AtomicLong errorCount, 
                                AtomicLong totalRequests,
                                java.util.concurrent.ConcurrentLinkedQueue<Long> responseTimes) {
        long requestsPerThread = request.getRequestsPerThread();
        String key = request.getKeyPrefix() != null ? request.getKeyPrefix() : "benchmark";
        int tokensPerRequest = request.getTokensPerRequest();
        long startNano = System.nanoTime();
        long durationNano = (long) request.getDurationSeconds() * 1_000_000_000L;
        
        // Use nanoseconds for precise pacing
        boolean hasFixedDelay = request.getDelayBetweenRequestsMs() != null && request.getDelayBetweenRequestsMs() > 0;
        long delayNano = hasFixedDelay ? (long) (request.getDelayBetweenRequestsMs() * 1_000_000L) : 0;

        long currentRequestCount = 0;
        // Schedule the first request to start immediately
        long nextRequestStartNano = System.nanoTime();
        
        dev.bnacar.distributedratelimiter.ratelimit.RateLimitAlgorithm algorithm = null;
        if (request.getAlgorithmOverride() != null && !request.getAlgorithmOverride().equals("none")) {
            try { algorithm = dev.bnacar.distributedratelimiter.ratelimit.RateLimitAlgorithm.valueOf(request.getAlgorithmOverride().toUpperCase().replace('-', '_')); } catch (Exception ignored) {}
        }

        while (currentRequestCount < requestsPerThread && (System.nanoTime() - startNano < durationNano)) {
            // Precise pacing: wait until the scheduled start time for the NEXT request
            if (hasFixedDelay) {
                while (System.nanoTime() < nextRequestStartNano) {
                    // Busy spin for sub-millisecond precision
                    if (delayNano > 10_000_000L) { // If > 10ms, yield to be nice to other processes
                        Thread.yield();
                    }
                }
                // Schedule next start time relative to CURRENT scheduled start time
                // This prevents drift even if a single request takes longer than the interval
                nextRequestStartNano += delayNano;
            }

            long requestStart = System.nanoTime();
            try {
                if (loadBalancerUrl != null && !loadBalancerUrl.isEmpty()) {
                    dev.bnacar.distributedratelimiter.models.RateLimitRequest apiReq = new dev.bnacar.distributedratelimiter.models.RateLimitRequest(key, tokensPerRequest);
                    apiReq.setAlgorithm(algorithm);
                    
                    // Create a per-request factory to apply the specific timeout
                    org.springframework.http.client.SimpleClientHttpRequestFactory requestFactory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
                    requestFactory.setConnectTimeout(request.getTimeoutMs());
                    requestFactory.setReadTimeout(request.getTimeoutMs());
                    org.springframework.web.client.RestTemplate timedRestTemplate = new org.springframework.web.client.RestTemplate(requestFactory);

                    ResponseEntity<dev.bnacar.distributedratelimiter.models.RateLimitResponse> response = 
                        timedRestTemplate.postForEntity(loadBalancerUrl + "/api/ratelimit/check", apiReq, dev.bnacar.distributedratelimiter.models.RateLimitResponse.class);
                    
                    if (response.getBody() != null && response.getBody().isAllowed()) {
                        successCount.incrementAndGet();
                    }
                } else {
                    if (rateLimiterService.isAllowed(key, tokensPerRequest, algorithm)) {
                        successCount.incrementAndGet();
                    }
                }
            } catch (HttpClientErrorException.TooManyRequests e) {
                // Denied (429) is normal
            } catch (Exception e) {
                errorCount.incrementAndGet();
            } finally {
                responseTimes.add(System.nanoTime() - requestStart);
                totalRequests.incrementAndGet();
                currentRequestCount++;
            }
        }
    }
}