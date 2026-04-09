package dev.bnacar.distributedratelimiter.models;

public class RateLimitResponse { 
    // Required for Jackson deserialization 
    private RateLimitResponse() { this(null, 0, false, -1, 0, null); }
    private final String key;
    private final int tokensRequested;
    private final boolean allowed;
    private final int remainingTokens;
    private final int capacity;
    private final AdaptiveInfo adaptiveInfo;
    private String algorithm;

    public RateLimitResponse(String key, int tokensRequested, boolean allowed) {
        this(key, tokensRequested, allowed, -1, 0, null);
    }

    public RateLimitResponse(String key, int tokensRequested, boolean allowed, int remainingTokens) {
        this(key, tokensRequested, allowed, remainingTokens, 0, null);
    }

    public RateLimitResponse(String key, int tokensRequested, boolean allowed, int remainingTokens, int capacity, AdaptiveInfo adaptiveInfo) {
        this.key = key;
        this.tokensRequested = tokensRequested;
        this.allowed = allowed;
        this.remainingTokens = remainingTokens;
        this.capacity = capacity;
        this.adaptiveInfo = adaptiveInfo;
    }

    public String getKey() {
        return key;
    }

    public int getTokensRequested() {
        return tokensRequested;
    }

    public boolean isAllowed() {
        return allowed;
    }

    public int getRemainingTokens() {
        return remainingTokens;
    }

    public int getCapacity() {
        return capacity;
    }

    public AdaptiveInfo getAdaptiveInfo() {
        return adaptiveInfo;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
    }
}
