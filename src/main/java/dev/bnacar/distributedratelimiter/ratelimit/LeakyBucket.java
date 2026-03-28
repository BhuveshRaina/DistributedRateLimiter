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
 */
public class LeakyBucket implements RateLimiter {
    
    private final int queueCapacity;
    private final double leakRatePerSecond;
    private final long maxQueueTimeMs;
    private final BlockingQueue<QueuedRequest> requestQueue;
    private final ScheduledExecutorService leakExecutor;
    private final AtomicLong lastLeakTime;
    private final AtomicInteger currentSimulatedQueueSize = new AtomicInteger(0);
    private volatile boolean shutdown = false;
    
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
    
    public LeakyBucket(int queueCapacity, double leakRatePerSecond) {
        this(queueCapacity, leakRatePerSecond, 5000);
    }
    
    public LeakyBucket(int queueCapacity, double leakRatePerSecond, long maxQueueTimeMs) {
        this.queueCapacity = queueCapacity;
        this.leakRatePerSecond = leakRatePerSecond;
        this.maxQueueTimeMs = maxQueueTimeMs;
        this.requestQueue = new LinkedBlockingQueue<>();
        this.lastLeakTime = new AtomicLong(System.currentTimeMillis());
        
        this.leakExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "LeakyBucket-Leak");
            t.setDaemon(true);
            return t;
        });
        
        startLeakProcessing();
    }
    
    @Override
    public synchronized boolean tryConsume(int tokens) {
        return tryConsumeWithResult(tokens).allowed;
    }

    @Override
    public synchronized ConsumptionResult tryConsumeWithResult(int tokens) {
        if (tokens <= 0 || shutdown) {
            return new ConsumptionResult(false, getCurrentTokens(), queueCapacity);
        }
        
        long currentTime = System.currentTimeMillis();
        long elapsedMs = currentTime - lastLeakTime.get();
        
        if (elapsedMs > 0) {
            double leaked = (elapsedMs / 1000.0) * leakRatePerSecond;
            if (leaked >= 1.0) {
                int tokensToClear = (int) leaked;
                int currentSize = currentSimulatedQueueSize.get();
                int newSize = Math.max(0, currentSize - tokensToClear);
                currentSimulatedQueueSize.set(newSize);
                
                long timeConsumedMs = (long) (tokensToClear * 1000.0 / leakRatePerSecond);
                lastLeakTime.addAndGet(timeConsumedMs);
            }
        }

        if (currentSimulatedQueueSize.get() + tokens > queueCapacity) {
            return new ConsumptionResult(false, queueCapacity - currentSimulatedQueueSize.get(), queueCapacity);
        }
        
        currentSimulatedQueueSize.addAndGet(tokens);
        return new ConsumptionResult(true, queueCapacity - currentSimulatedQueueSize.get(), queueCapacity);
    }
    
    @Override
    public int getCurrentTokens() {
        return Math.max(0, queueCapacity - currentSimulatedQueueSize.get());
    }

    @Override
    public void setCurrentTokens(int tokens) {
        currentSimulatedQueueSize.set(Math.max(0, queueCapacity - tokens));
    }
    
    @Override
    public int getCapacity() {
        return queueCapacity;
    }
    
    @Override
    public int getRefillRate() {
        return (int) Math.ceil(leakRatePerSecond);
    }
    
    @Override
    public long getLastRefillTime() {
        return lastLeakTime.get();
    }

    public double getLeakRatePerSecond() {
        return leakRatePerSecond;
    }

    public long getMaxQueueTimeMs() {
        return maxQueueTimeMs;
    }

    public int getQueueSize() {
        return currentSimulatedQueueSize.get();
    }

    public CompletableFuture<Boolean> enqueueRequest(int tokens) {
        if (tokens <= 0 || shutdown) {
            return CompletableFuture.completedFuture(false);
        }
        
        if (currentSimulatedQueueSize.get() + tokens > queueCapacity) {
            return CompletableFuture.completedFuture(false);
        }
        
        QueuedRequest request = new QueuedRequest(tokens);
        requestQueue.offer(request);
        currentSimulatedQueueSize.addAndGet(tokens);
        return request.future;
    }
    
    private void startLeakProcessing() {
        long intervalMs = Math.max(10, (long) (1000 / leakRatePerSecond / 10));
        leakExecutor.scheduleWithFixedDelay(this::processLeakage, 0, intervalMs, TimeUnit.MILLISECONDS);
        leakExecutor.scheduleWithFixedDelay(this::cleanupTimedOutRequests, 1000, 1000, TimeUnit.MILLISECONDS);
    }
    
    private void processLeakage() {
        if (shutdown || requestQueue.isEmpty()) return;
        long currentTime = System.currentTimeMillis();
        long lastLeak = lastLeakTime.get();
        long timeSinceLastLeak = currentTime - lastLeak;
        double tokensToProcess = (timeSinceLastLeak / 1000.0) * leakRatePerSecond;
        
        if (tokensToProcess >= 1.0) {
            int tokensProcessed = 0;
            int maxTokens = (int) Math.floor(tokensToProcess);
            while (tokensProcessed < maxTokens && !requestQueue.isEmpty()) {
                QueuedRequest request = requestQueue.poll();
                if (request != null) {
                    tokensProcessed += request.tokens;
                    request.future.complete(true);
                }
            }
            if (tokensProcessed > 0) lastLeakTime.set(currentTime);
        }
    }
    
    private void cleanupTimedOutRequests() {
        if (shutdown) return;
        long currentTime = System.currentTimeMillis();
        while (!requestQueue.isEmpty()) {
            QueuedRequest request = requestQueue.peek();
            if (request != null && (currentTime - request.enqueuedTime) > maxQueueTimeMs) {
                requestQueue.poll();
                request.future.complete(false);
            } else {
                break;
            }
        }
    }
    
    public void shutdown() {
        shutdown = true;
        leakExecutor.shutdown();
        while (!requestQueue.isEmpty()) {
            QueuedRequest request = requestQueue.poll();
            if (request != null) request.future.complete(false);
        }
        try {
            if (!leakExecutor.awaitTermination(1, TimeUnit.SECONDS)) leakExecutor.shutdownNow();
        } catch (InterruptedException e) {
            leakExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}