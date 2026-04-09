package dev.bnacar.distributedratelimiter.ratelimit;

/**
 * Configuration for rate limiting parameters.
 */
public class RateLimitConfig {
    private int capacity;
    private int refillRate;
    private long cleanupIntervalMs;
    private RateLimitAlgorithm algorithm;
    private boolean adaptiveEnabled;

    public RateLimitConfig() {
        // Default constructor for Jackson
    }

    public RateLimitConfig(int capacity, int refillRate, long cleanupIntervalMs, RateLimitAlgorithm algorithm, boolean adaptiveEnabled) {
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.cleanupIntervalMs = cleanupIntervalMs;
        this.algorithm = algorithm;
        this.adaptiveEnabled = adaptiveEnabled;
    }

    public RateLimitConfig(int capacity, int refillRate, long cleanupIntervalMs, RateLimitAlgorithm algorithm) {
        this(capacity, refillRate, cleanupIntervalMs, algorithm, true);
    }

    public RateLimitConfig(int capacity, int refillRate, long cleanupIntervalMs) {
        this(capacity, refillRate, cleanupIntervalMs, RateLimitAlgorithm.TOKEN_BUCKET, true);
    }

    public RateLimitConfig(int capacity, int refillRate) {
        this(capacity, refillRate, 60000, RateLimitAlgorithm.TOKEN_BUCKET, true); // Default 60s cleanup interval
    }

    public int getCapacity() {
        return capacity;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    public int getRefillRate() {
        return refillRate;
    }

    public void setRefillRate(int refillRate) {
        this.refillRate = refillRate;
    }

    public long getCleanupIntervalMs() {
        return cleanupIntervalMs;
    }

    public void setCleanupIntervalMs(long cleanupIntervalMs) {
        this.cleanupIntervalMs = cleanupIntervalMs;
    }

    public RateLimitAlgorithm getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(RateLimitAlgorithm algorithm) {
        this.algorithm = algorithm;
    }

    public boolean isAdaptiveEnabled() {
        return adaptiveEnabled;
    }

    public void setAdaptiveEnabled(boolean adaptiveEnabled) {
        this.adaptiveEnabled = adaptiveEnabled;
    }

    @Override
    public String toString() {
        return "RateLimitConfig{" +
                "capacity=" + capacity +
                ", refillRate=" + refillRate +
                ", cleanupIntervalMs=" + cleanupIntervalMs +
                ", algorithm=" + algorithm +
                ", adaptiveEnabled=" + adaptiveEnabled +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RateLimitConfig that = (RateLimitConfig) o;
        return capacity == that.capacity && 
               refillRate == that.refillRate && 
               cleanupIntervalMs == that.cleanupIntervalMs &&
               algorithm == that.algorithm &&
               adaptiveEnabled == that.adaptiveEnabled;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(capacity, refillRate, cleanupIntervalMs, algorithm, adaptiveEnabled);
    }
}