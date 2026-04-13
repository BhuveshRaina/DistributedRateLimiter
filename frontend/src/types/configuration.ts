export type ConfigAlgorithm = "token-bucket" | "sliding-window" | "fixed-window" | "leaky-bucket";

export interface GlobalConfig {
  defaultCapacity: number;
  defaultRefillRate: number;
  cleanupInterval: number;
  algorithm: ConfigAlgorithm;
}

export interface KeyConfig {
  id: string;
  keyName: string;
  capacity: number;
  refillRate: number;
  algorithm: ConfigAlgorithm;
  adaptiveEnabled: boolean;
  shadowMode: boolean;
  effectiveCapacity?: number;
  effectiveRefillRate?: number;
  createdAt: string;
  updatedAt: string;
}

export interface PatternConfig {
  id: string;
  pattern: string;
  capacity: number;
  refillRate: number;
  algorithm: ConfigAlgorithm;
  adaptiveEnabled: boolean;
  shadowMode: boolean;
  effectiveCapacity?: number;
  effectiveRefillRate?: number;
  description: string;
  createdAt: string;
  updatedAt: string;
}

export interface ConfigStats {
  totalKeyConfigs: number;
  totalPatternConfigs: number;
  mostUsedPattern: string;
  cacheHitRate: number;
  avgLookupTime: number;
}
