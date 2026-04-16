package dev.bnacar.distributedratelimiter.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class RedisRateLimiterConfiguration {
    
    @Bean
    @ConditionalOnProperty(name = "ratelimiter.redis.enabled", havingValue = "true", matchIfMissing = true)
    public RedisTemplate<String, Object> rateLimiterRedisTemplate(RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        
        // Use Jackson for objects, but we'll handle metrics as strings/longs manually where needed
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer(objectMapper));
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer(objectMapper));
        
        template.setEnableTransactionSupport(false);
        template.afterPropertiesSet();
        return template;
    }
    
    @Bean(name = "rateLimiterTaskExecutor")
    public Executor rateLimiterTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("RateLimiter-Async-");
        executor.initialize();
        return executor;
    }
}
