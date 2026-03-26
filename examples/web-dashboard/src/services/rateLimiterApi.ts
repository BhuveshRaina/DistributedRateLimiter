// Real API Service for Distributed Rate Limiter Backend

// Use relative URLs in development (Vite proxy) and production (same origin)
// In development, Vite proxy forwards /api/* and /actuator/* to http://localhost:8080
const API_BASE_URL = import.meta.env.PROD ? '' : '';
const ADMIN_CREDENTIALS = btoa('admin:admin123');

interface RateLimitCheckRequest {
  key: string;
  tokens: number;
  apiKey?: string;
}

interface RateLimitCheckResponse {
  key: string;
  tokensRequested: number;
  allowed: boolean;
  remainingTokens: number;
}

interface RateLimiterConfig {
  capacity: number;
  refillRate: number;
  cleanupIntervalMs: number;
  keyConfigs: Record<string, { capacity: number; refillRate: number; algorithm?: string }>;
  patternConfigs: Record<string, { capacity: number; refillRate: number; algorithm?: string }>;
}

interface KeyConfig {
  capacity: number;
  refillRate: number;
  algorithm?: string;
}

interface PatternConfig {
  capacity: number;
  refillRate: number;
}

interface ActiveKey {
  key: string;
  capacity: number;
  algorithm: string;
  lastAccessTime: number;
  active: boolean;
  isActive?: boolean;
  cleanupIntervalMs?: number;
}

interface ApiKeyInfo {
  key: string;
  name: string;
  description: string;
  createdAt: string;
  status: string;
}

interface AdminKeysResponse {
  keys: ActiveKey[];
  totalKeys: number;
  activeKeys: number;
}

interface LoadTestRequest {
  concurrentThreads: number;
  requestsPerThread: number;
  durationSeconds: number;
  tokensPerRequest: number;
  delayBetweenRequestsMs?: number;
  keyPrefix?: string;
  algorithmOverride?: string;
  timeoutMs?: number;
  customHeaders?: Record<string, string>;
}

// BenchmarkResponse from backend (aligned with BenchmarkResponse.java)
interface LoadTestResponse {
  success: boolean;
  errorMessage?: string;
  totalRequests: number;
  successfulRequests: number;
  errorRequests: number;
  throughputPerSecond: number;
  successRate: number;
  durationSeconds: number;
  concurrentThreads: number;
  requestsPerThread: number;
  avgResponseTimeMs: number;
  p50ResponseTimeMs: number;
  p95ResponseTimeMs: number;
  p99ResponseTimeMs: number;
}

interface KeyMetric {
  allowedRequests: number;
  deniedRequests: number;
  lastAccessTime: number;
}

interface AlgorithmMetrics {
  allowedRequests: number;
  deniedRequests: number;
  totalProcessingTimeMs: number;
  successRate: number;
  avgResponseTimeMs: number;
}

interface RateLimitEvent {
  id: string;
  timestamp: number;
  key: string;
  algorithm: string;
  allowed: boolean;
  tokens: number;
}

interface SystemMetrics {
  keyMetrics: Record<string, KeyMetric>;
  perAlgorithmMetrics: Record<string, AlgorithmMetrics>;
  recentEvents: RateLimitEvent[];
  totalAllowedRequests: number;
  totalDeniedRequests: number;
  totalProcessingTimeMs: number;
  redisConnected: boolean;
}

interface HealthResponse {
  status: string;
  components: {
    redis: { status: string };
    rateLimiter: { status: string };
  };
}

interface ErrorResponse {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  path: string;
}

class RateLimiterApiService {
  private async request<T>(
    endpoint: string,
    options: RequestInit = {}
  ): Promise<T> {
    const url = `${API_BASE_URL}${endpoint}`;
    
    try {
      const response = await fetch(url, {
        ...options,
        headers: {
          'Content-Type': 'application/json',
          ...options.headers,
        },
      });

      if (!response.ok) {
        if (response.status === 429) {
          const error: ErrorResponse = await response.json();
          throw new Error(error.message || 'Rate limit exceeded');
        }
        
        if (response.headers.get('content-type')?.includes('application/json')) {
          const error: ErrorResponse = await response.json();
          throw new Error(error.message || `HTTP ${response.status}`);
        }
        
        throw new Error(`HTTP ${response.status}: ${response.statusText}`);
      }

      // Check if response has content and is JSON (support specialized JSON types like actuator)
      const contentType = response.headers.get('content-type');
      if (contentType && (contentType.includes('application/json') || contentType.includes('+json'))) {
        const text = await response.text();
        return text ? JSON.parse(text) : {} as T;
      }
      
      // For non-JSON or empty responses, return as text or empty object
      const text = await response.text();
      return text as unknown as T;
    } catch (error) {
      if (error instanceof Error) {
        throw error;
      }
      throw new Error('Network error occurred');
    }
  }

  private async adminRequest<T>(
    endpoint: string,
    options: RequestInit = {}
  ): Promise<T> {
    return this.request<T>(endpoint, {
      ...options,
      headers: {
        ...options.headers,
        Authorization: `Basic ${ADMIN_CREDENTIALS}`,
      },
    });
  }

  // ============ RATE LIMITING ============
  
  async checkRateLimit(request: RateLimitCheckRequest): Promise<RateLimitCheckResponse> {
    return this.request<RateLimitCheckResponse>('/api/ratelimit/check', {
      method: 'POST',
      body: JSON.stringify(request),
    });
  }

  // ============ CONFIGURATION ============
  
  async getConfig(): Promise<RateLimiterConfig> {
    return this.request<RateLimiterConfig>('/api/ratelimit/config');
  }

  async getConfigStats(): Promise<Record<string, number>> {
    return this.request<Record<string, number>>('/api/ratelimit/config/stats');
  }

  async updateDefaultConfig(config: { capacity?: number; refillRate?: number; cleanupIntervalMs?: number }): Promise<void> {
    await this.request('/api/ratelimit/config/default', {
      method: 'POST',
      body: JSON.stringify(config),
    });
  }

  async updateKeyConfig(key: string, config: KeyConfig): Promise<void> {
    await this.request(`/api/ratelimit/config/keys/${encodeURIComponent(key)}`, {
      method: 'POST',
      body: JSON.stringify(config),
    });
  }

  async updatePatternConfig(pattern: string, config: PatternConfig): Promise<void> {
    await this.request(`/api/ratelimit/config/patterns/${encodeURIComponent(pattern)}`, {
      method: 'POST',
      body: JSON.stringify(config),
    });
  }

  async deleteKeyConfig(key: string): Promise<void> {
    await this.request(`/api/ratelimit/config/keys/${encodeURIComponent(key)}`, {
      method: 'DELETE',
    });
  }

  async deletePatternConfig(pattern: string): Promise<void> {
    await this.request(`/api/ratelimit/config/patterns/${encodeURIComponent(pattern)}`, {
      method: 'DELETE',
    });
  }

  // ============ ADMIN - ACTIVE KEYS ============
  
  async getActiveKeys(): Promise<AdminKeysResponse> {
    const data = await this.adminRequest<AdminKeysResponse>('/admin/keys');
    console.debug('Active keys from backend:', data);
    return data;
  }

  // ============ AUTHENTICATION API KEYS ============
  
  async getAuthApiKeys(): Promise<ApiKeyInfo[]> {
    return this.request<ApiKeyInfo[]>('/admin/api-keys');
  }

  async addAuthApiKey(request: { key: string; name: string; description?: string }): Promise<void> {
    await this.request('/admin/api-keys', {
      method: 'POST',
      body: JSON.stringify(request),
    });
  }

  async deleteAuthApiKey(key: string): Promise<void> {
    await this.request(`/admin/api-keys/${encodeURIComponent(key)}`, {
      method: 'DELETE',
    });
  }

  // ============ LOAD TESTING ============
  
  async runLoadTest(request: LoadTestRequest): Promise<LoadTestResponse> {
    return this.request<LoadTestResponse>('/api/benchmark/run', {
      method: 'POST',
      body: JSON.stringify(request),
    });
  }

  // ============ METRICS ============
  
  async getMetrics(): Promise<SystemMetrics> {
    return this.request<SystemMetrics>('/metrics');
  }

  // ============ HEALTH CHECK ============
  
  async healthCheck(): Promise<HealthResponse> {
    return this.request<HealthResponse>('/actuator/health');
  }

  // ============ SCHEDULING ============
  
  async getSchedules(): Promise<ScheduleResponse[]> {
    return this.request<ScheduleResponse[]>('/api/ratelimit/schedule');
  }

  async getSchedule(name: string): Promise<ScheduleResponse> {
    return this.request<ScheduleResponse>(`/api/ratelimit/schedule/${encodeURIComponent(name)}`);
  }

  async createSchedule(request: ScheduleRequest): Promise<void> {
    await this.request('/api/ratelimit/schedule', {
      method: 'POST',
      body: JSON.stringify(request),
    });
  }

  async updateSchedule(name: string, request: ScheduleRequest): Promise<void> {
    await this.request(`/api/ratelimit/schedule/${encodeURIComponent(name)}`, {
      method: 'PUT',
      body: JSON.stringify(request),
    });
  }

  async deleteSchedule(name: string): Promise<void> {
    await this.request(`/api/ratelimit/schedule/${encodeURIComponent(name)}`, {
      method: 'DELETE',
    });
  }

  async activateSchedule(name: string): Promise<void> {
    await this.request(`/api/ratelimit/schedule/${encodeURIComponent(name)}/activate`, {
      method: 'POST',
    });
  }

  async deactivateSchedule(name: string): Promise<void> {
    await this.request(`/api/ratelimit/schedule/${encodeURIComponent(name)}/deactivate`, {
      method: 'POST',
    });
  }

  async createEmergencySchedule(request: EmergencyScheduleRequest): Promise<void> {
    await this.request('/api/ratelimit/schedule/emergency', {
      method: 'POST',
      body: JSON.stringify(request),
    });
  }

  // ============ ADAPTIVE RATE LIMITING ============
  
  async getAdaptiveStatus(key: string): Promise<AdaptiveStatus> {
    return this.request<AdaptiveStatus>(`/api/ratelimit/adaptive/${encodeURIComponent(key)}/status`);
  }

  async getAdaptiveConfig(): Promise<AdaptiveConfig> {
    return this.request<AdaptiveConfig>('/api/ratelimit/adaptive/config');
  }

  async getAllAdaptiveStatuses(): Promise<AdaptiveStatus[]> {
    return this.request<AdaptiveStatus[]>('/api/ratelimit/adaptive/all-statuses');
  }

  async setAdaptiveOverride(key: string, override: AdaptiveOverrideRequest): Promise<void> {
    await this.request(`/api/ratelimit/adaptive/${encodeURIComponent(key)}/override`, {
      method: 'POST',
      body: JSON.stringify(override),
    });
  }

  async removeAdaptiveOverride(key: string): Promise<void> {
    await this.request(`/api/ratelimit/adaptive/${encodeURIComponent(key)}/override`, {
      method: 'DELETE',
    });
  }

  // Get adaptive status for all active keys (using bulk endpoint for reliability)
  async getAdaptiveStatusForAllKeys(): Promise<AdaptiveStatus[]> {
    try {
      return await this.getAllAdaptiveStatuses();
    } catch (error) {
      console.error('Failed to get adaptive status for all keys:', error);
      return [];
    }
  }
}

// Import schedule types
import type { 
  ScheduleRequest, 
  ScheduleResponse, 
  EmergencyScheduleRequest 
} from '@/types/scheduling';

// Import adaptive types
import type {
  AdaptiveStatus,
  AdaptiveConfig,
  AdaptiveOverrideRequest,
} from '@/types/adaptive';

export const rateLimiterApi = new RateLimiterApiService();

// Export types for use in components
export type {
  RateLimitCheckRequest,
  RateLimitCheckResponse,
  RateLimiterConfig,
  KeyConfig,
  PatternConfig,
  ActiveKey,
  AdminKeysResponse,
  LoadTestRequest,
  LoadTestResponse,
  KeyMetric,
  SystemMetrics,
  HealthResponse,
  ErrorResponse,
  ScheduleRequest,
  ScheduleResponse,
  EmergencyScheduleRequest,
  AdaptiveStatus,
  AdaptiveConfig,
  AdaptiveOverrideRequest,
};
