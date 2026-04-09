package dev.bnacar.distributedratelimiter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.ArrayList;

@Configuration
@ConfigurationProperties(prefix = "ratelimiter.security")
public class SecurityConfiguration {

    private ApiKeys apiKeys = new ApiKeys();

    public static class ApiKeys {
        private boolean enabled = true;
        private List<String> validKeys = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getValidKeys() {
            return validKeys != null ? new ArrayList<>(validKeys) : new ArrayList<>();
        }

        public void setValidKeys(List<String> validKeys) {
            this.validKeys = validKeys != null ? new ArrayList<>(validKeys) : new ArrayList<>();
        }
    }

    public ApiKeys getApiKeys() {
        return apiKeys;
    }

    public void setApiKeys(ApiKeys apiKeys) {
        this.apiKeys = apiKeys;
    }
}