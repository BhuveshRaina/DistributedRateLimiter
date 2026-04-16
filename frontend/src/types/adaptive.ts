// Types for Adaptive Rate Limiting API responses

export interface CurrentLimits {
  capacity: number;
  refillRate: number;
}

export interface RecommendedLimits {
  capacity: number;
  refillRate: number;
}

export interface AdaptiveStatusInfo {
  mode: 'STATIC' | 'ADAPTIVE' | 'LEARNING' | 'OVERRIDE' | 'DISABLED';
  recommendedLimits: RecommendedLimits;
  reasoning: Record<string, string>;
  adaptiveEnabled: boolean;
}

export interface AdaptiveStatus {
  key: string;
  displayName: string;
  adaptiveEnabled: boolean;
  currentLimits: CurrentLimits;
  originalLimits: CurrentLimits;
  adaptiveStatus: AdaptiveStatusInfo;
  timestamp: string;
}

export interface AdaptiveConfig {
  enabled: boolean;
  evaluationIntervalMs: number;
  maxAdjustmentFactor: number;
  minCapacity: number;
  maxCapacity: number;
}

export interface AdaptiveOverrideRequest {
  capacity: number;
  refillRate: number;
  reason: string;
}

export interface AdaptiveInfo {
  originalLimits: CurrentLimits;
  currentLimits: CurrentLimits;
  adaptationReason: string;
  adjustmentTimestamp: string;
  nextEvaluationIn: string;
}

// Enhanced rate limit response with adaptive info
export interface EnhancedRateLimitCheckResponse {
  key: string;
  tokensRequested: number;
  allowed: boolean;
  adaptiveInfo?: AdaptiveInfo;
}

// Adaptive key summary for dashboard
export interface AdaptiveKeySummary {
  key: string;
  mode: string;
  currentCapacity: number;
  originalCapacity: number;
  adaptationReason: string;
  lastUpdate: string;
}
