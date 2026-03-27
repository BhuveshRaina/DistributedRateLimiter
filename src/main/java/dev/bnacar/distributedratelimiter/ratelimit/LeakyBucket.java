package dev.bnacar.distributedratelimiter.ratelimit;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Leaky bucket rate limiter implementation.
 * Enforces constant output rate through request queuing and processing.
 * Provides traffic shaping with predictable processing rates regardless of input bursts.
 */
public class LeakyBucket implements RateLimiter {
    
    private final int queueCapacity;
    private final double leakRatePerSecond;
    private final long maxQueueTimeMs;
    private final BlockingQueue<QueuedRequest> requestQueue;
    private final ScheduledExecutorService leakExecutor;
    private final AtomicLong lastLeakTime;
    private volatile boolean shutdown = false;
    
    /**
     * Represents a queued request waiting to be processed.
     */
    private static class QueuedRequest {
        final CompletableFuture<Boolean> future;
        final long enqueuedTime;
        final int tokens;
        
        QueuedRequest(int tokens) {
            this.future = new CompletableFuture<>();
            this.enqueuedTime = System.currentTimeMillis();
            this.tokens = tokens;
        }
    }
    
    /**
     * Create a new leaky bucket with specified parameters.
     * 
     * @param queueCapacity Maximum number of requests that can be queued
     * @param leakRatePerSecond Rate at which requests are processed (tokens per second)
     */
    public LeakyBucket(int queueCapacity, double leakRatePerSecond) {
        this(queueCapacity, leakRatePerSecond, 5000); // Default 5 second max queue time
    }
    
    /**
     * Create a new leaky bucket with specified parameters.
     * 
     * @param queueCapacity Maximum number of requests that can be queued
     * @param leakRatePerSecond Rate at which requests are processed (tokens per second)
     * @param maxQueueTimeMs Maximum time a request can wait in queue before timing out
     */
    public LeakyBucket(int queueCapacity, double leakRatePerSecond, long maxQueueTimeMs) {
        this.queueCapacity = queueCapacity;
        this.leakRatePerSecond = leakRatePerSecond;
        this.maxQueueTimeMs = maxQueueTimeMs;
        this.requestQueue = new LinkedBlockingQueue<>();
        this.lastLeakTime = new AtomicLong(System.currentTimeMillis());
        
        // Create dedicated executor for leak processing
        this.leakExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "LeakyBucket-Leak");
            t.setDaemon(true);
            return t;
        });
        
        // Start leak processing - process every 100ms for smooth operation
        startLeakProcessing();
    }
    
    @Override
    public synchronized boolean tryConsume(int tokens) {
        if (tokens <= 0 || shutdown) {
            return false;
        }
        
        long currentTime = System.currentTimeMillis();
        long elapsedMs = currentTime - lastLeakTime.get();
        
        // Calculate how many tokens have "leaked" (processed) since last time
        // Leaky bucket strictly processes tokens over time
        if (elapsedMs > 0) {
            double leaked = (elapsedMs / 1000.0) * leakRatePerSecond;
            if (leaked >= 1.0) {
                int tokensToClear = (int) leaked;
                // Reduce simulated queue size
                int currentSize = currentSimulatedQueueSize.get();
                int newSize = Math.max(0, currentSize - tokensToClear);
                currentSimulatedQueueSize.set(newSize);
                
                // Update lastLeakTime by the exact duration used to leak whole tokens
                long timeConsumedMs = (long) (tokensToClear * 1000.0 / leakRatePerSecond);
                lastLeakTime.addAndGet(timeConsumedMs);
            }
        }

        // For Leaky Bucket, if capacity is 1, it must process before next request
        // If capacity is 5, it acts like a queue of 5.
        // If tokens requested exceeds current capacity, it fails immediately (no burst beyond queue)
        if (currentSimulatedQueueSize.get() + tokens > queueCapacity) {
            return false;
        }
        
        currentSimulatedQueueSize.addAndGet(tokens);
        return true;
    }
    
    // Add this field to the class
    private final AtomicInteger currentSimulatedQueueSize = new AtomicInteger(0);
    
    /**
     * Calculate estimated time to process current queue plus new request.
     */
    private long calculateEstimatedProcessingTime() {
        int currentQueueSize = requestQueue.size();
        double processingTimePerToken = 1000.0 / leakRatePerSecond; // ms per token
        return (long) (currentQueueSize * processingTimePerToken);
    }
    
    @Override
    public int getCurrentTokens() {
        // For leaky bucket, return available queue capacity
        return Math.max(0, queueCapacity - requestQueue.size());
    }
    
    @Override
    public int getCapacity() {
        return queueCapacity;
    }
    
    @Override
    public int getRefillRate() {
        // Return leak rate as integer for interface compatibility
        return (int) Math.ceil(leakRatePerSecond);
    }
    
    @Override
    public long getLastRefillTime() {
        return lastLeakTime.get();
    }
    
    /**
     * Get the leak rate in requests per second.
     */
    public double getLeakRatePerSecond() {
        return leakRatePerSecond;
    }
    
    /**
     * Get the maximum queue wait time in milliseconds.
     */
    public long getMaxQueueTimeMs() {
        return maxQueueTimeMs;
    }
    
    /**
     * Get the current queue size.
     */
    public int getQueueSize() {
        return requestQueue.size();
    }
    
    /**
     * Start the leak processing task.
     */
    private void startLeakProcessing() {
        // Calculate interval based on leak rate for smooth processing
        long intervalMs = Math.max(10, (long) (1000 / leakRatePerSecond / 10)); // Process 10x per token interval
        
        leakExecutor.scheduleWithFixedDelay(
            this::processLeakage,
            0,
            intervalMs,
            TimeUnit.MILLISECONDS
        );
        
        // Also schedule timeout cleanup
        leakExecutor.scheduleWithFixedDelay(
            this::cleanupTimedOutRequests,
            1000, // Start after 1 second
            1000, // Run every second
            TimeUnit.MILLISECONDS
        );
    }
    
    /**
     * Process queued requests at the configured leak rate.
     */
    private void processLeakage() {
        if (shutdown || requestQueue.isEmpty()) {
            return;
        }
        
        long currentTime = System.currentTimeMillis();
        long lastLeak = lastLeakTime.get();
        long timeSinceLastLeak = currentTime - lastLeak;
        
        // Calculate how many tokens we can process based on elapsed time
        double tokensToProcess = (timeSinceLastLeak / 1000.0) * leakRatePerSecond;
        
        if (tokensToProcess >= 1.0) {
            int tokensProcessed = 0;
            int maxTokens = (int) Math.floor(tokensToProcess);
            
            // Process requests from queue
            while (tokensProcessed < maxTokens && !requestQueue.isEmpty()) {
                QueuedRequest request = requestQueue.poll();
                if (request != null) {
                    tokensProcessed += request.tokens;
                    request.future.complete(true);
                }
            }
            
            if (tokensProcessed > 0) {
                lastLeakTime.set(currentTime);
            }
        }
    }
    
    /**
     * Clean up requests that have been in the queue too long.
     */
    private void cleanupTimedOutRequests() {
        if (shutdown) {
            return;
        }
        
        long currentTime = System.currentTimeMillis();
        
        // Check for timed out requests at the front of the queue
        while (!requestQueue.isEmpty()) {
            QueuedRequest request = requestQueue.peek();
            if (request != null && (currentTime - request.enqueuedTime) > maxQueueTimeMs) {
                requestQueue.poll();
                request.future.complete(false); // Timeout
            } else {
                break; // Queue is ordered by time, so we can stop here
            }
        }
    }
    
    /**
     * Shutdown the leaky bucket and clean up resources.
     */
    public void shutdown() {
        shutdown = true;
        leakExecutor.shutdown();
        
        // Complete any remaining requests with false
        while (!requestQueue.isEmpty()) {
            QueuedRequest request = requestQueue.poll();
            if (request != null) {
                request.future.complete(false);
            }
        }
        
        try {
            if (!leakExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                leakExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            leakExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Asynchronously enqueue a request for processing.
     * This is the intended way to use the leaky bucket for true asynchronous operation.
     * 
     * @param tokens Number of tokens to consume
     * @return CompletableFuture that completes when the request is processed or times out
     */
    public CompletableFuture<Boolean> enqueueRequest(int tokens) {
        if (tokens <= 0 || shutdown) {
            return CompletableFuture.completedFuture(false);
        }
        
        // Check queue capacity
        if (requestQueue.size() >= queueCapacity) {
            return CompletableFuture.completedFuture(false);
        }
        
        QueuedRequest request = new QueuedRequest(tokens);
        if (requestQueue.offer(request)) {
            return request.future;
        } else {
            return CompletableFuture.completedFuture(false);
        }
    }
}