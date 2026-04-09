package dev.bnacar.distributedratelimiter.models;

import java.time.Instant;

/**
 * Information about an authentication API key.
 */
public class ApiKeyInfo {
    private final String key;
    private final String name;
    private final String description;
    private final String createdAt;
    private final String status;

    public ApiKeyInfo(String key, String name, String description, String createdAt, String status) {
        this.key = key;
        this.name = name;
        this.description = description;
        this.createdAt = createdAt;
        this.status = status;
    }

    public String getKey() {
        return key;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public String getStatus() {
        return status;
    }
}
