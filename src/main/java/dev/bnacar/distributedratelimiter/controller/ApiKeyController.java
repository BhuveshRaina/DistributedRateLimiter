package dev.bnacar.distributedratelimiter.controller;

import dev.bnacar.distributedratelimiter.models.ApiKeyCreateRequest;
import dev.bnacar.distributedratelimiter.models.ApiKeyInfo;
import dev.bnacar.distributedratelimiter.security.ApiKeyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST controller for managing authentication API keys.
 */
@RestController
@RequestMapping("/admin/api-keys")
@Tag(name = "API Key Management", description = "CRUD operations for authentication API keys")
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:3000", "http://127.0.0.1:5173", "http://127.0.0.1:3000", "http://[::1]:5173", "http://[::1]:3000"})
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    @Autowired
    public ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    /**
     * Get all active API keys with metadata.
     */
    @GetMapping
    @Operation(summary = "Get all API keys", description = "Retrieve a list of all currently active and valid authentication API keys with their metadata")
    public ResponseEntity<List<ApiKeyInfo>> getAllApiKeys() {
        Set<String> keys = apiKeyService.getAllApiKeys();
        List<ApiKeyInfo> keyInfos = new ArrayList<>();
        
        for (String key : keys) {
            Map<Object, Object> metadata = apiKeyService.getMetadata(key);
            String name = (String) metadata.getOrDefault("name", "Key " + key.substring(0, Math.min(8, key.length())));
            String description = (String) metadata.getOrDefault("description", "Authentication API key");
            String createdAt = (String) metadata.getOrDefault("createdAt", java.time.Instant.now().toString());
            String status = "active";
            
            keyInfos.add(new ApiKeyInfo(key, name, description, createdAt, status));
        }
        
        return ResponseEntity.ok(keyInfos);
    }

    /**
     * Add a new API key with metadata.
     */
    @PostMapping
    @Operation(summary = "Add an API key", description = "Register a new valid API key in the system with metadata")
    public ResponseEntity<String> addApiKey(@RequestBody ApiKeyCreateRequest request) {
        apiKeyService.addApiKey(request.getKey(), request.getName(), request.getDescription());
        return ResponseEntity.ok("API key added successfully");
    }

    /**
     * Add a new API key (legacy).
     */
    @PostMapping("/{key}")
    @Operation(summary = "Add an API key (legacy)", description = "Register a new valid API key in the system using just the key string")
    public ResponseEntity<String> addApiKeyLegacy(@PathVariable String key) {
        apiKeyService.addApiKey(key);
        return ResponseEntity.ok("API key added successfully");
    }

    /**
     * Delete an API key.
     */
    @DeleteMapping("/{key}")
    @Operation(summary = "Delete an API key", description = "Remove an API key from the list of valid keys")
    public ResponseEntity<String> deleteApiKey(@PathVariable String key) {
        apiKeyService.removeApiKey(key);
        return ResponseEntity.ok("API key deleted successfully");
    }
}
