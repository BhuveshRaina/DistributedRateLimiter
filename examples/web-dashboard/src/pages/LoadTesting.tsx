import { useState, useEffect, useRef } from "react";
import { TestConfigPanel } from "@/components/loadtest/TestConfigPanel";
import { AdvancedSettings } from "@/components/loadtest/AdvancedSettings";
import { TestExecution } from "@/components/loadtest/TestExecution";
import { LiveResults } from "@/components/loadtest/LiveResults";
import { TestResultsSummary } from "@/components/loadtest/TestResultsSummary";
import { HistoricalTests } from "@/components/loadtest/HistoricalTests";
import { rateLimiterApi } from "@/services/rateLimiterApi";
import { useApp } from "@/contexts/AppContext";
import { toast } from "sonner";
import {
  LoadTestConfig,
  LoadTestMetrics,
  LoadTestResult,
  TimeSeriesPoint,
} from "@/types/loadTesting";

const LoadTesting = () => {
  const { realtimeMetrics } = useApp();
  const [config, setConfig] = useState<LoadTestConfig>({
    targetKey: "rl_prod_user123",
    requestRate: 100,
    duration: 30,
    concurrency: 10,
    pattern: "constant",
    tokensPerRequest: 1,
    timeout: 5000,
  });

  const [isRunning, setIsRunning] = useState(false);
  const [progress, setProgress] = useState(0);
  const [metrics, setMetrics] = useState<LoadTestMetrics>({
    requestsSent: 0,
    successful: 0,
    failed: 0,
    rateLimited: 0,
    avgResponseTime: 0,
    p50ResponseTime: 0,
    p95ResponseTime: 0,
    p99ResponseTime: 0,
    successRate: 100,
    currentRate: 0,
  });
  const [timeSeriesData, setTimeSeriesData] = useState<TimeSeriesPoint[]>([]);
  const [currentResult, setCurrentResult] = useState<LoadTestResult | null>(null);
  const [historicalResults, setHistoricalResults] = useState<LoadTestResult[]>([]);
  const [baselineMetrics, setBaselineMetrics] = useState({ allowed: 0, denied: 0 });

  const abortControllerRef = useRef<AbortController | null>(null);
  const intervalRef = useRef<number | null>(null);
  const startTimeRef = useRef<number>(0);

  // Synchronize with global metrics during test for distributed consistency
  useEffect(() => {
    if (!isRunning || !realtimeMetrics || !config.targetKey) return;

    // Sum up metrics for all threads (e.g., user:123:1, user:123:2, etc.)
    let currentAllowed = 0;
    let currentDenied = 0;
    
    Object.entries(realtimeMetrics.keyMetrics).forEach(([key, data]) => {
      if (key.startsWith(config.targetKey)) {
        currentAllowed += data.allowedRequests;
        currentDenied += data.deniedRequests;
      }
    });

    // Calculate delta from baseline
    const totalAllowed = Math.max(0, currentAllowed - baselineMetrics.allowed);
    const totalDenied = Math.max(0, currentDenied - baselineMetrics.denied);

    if (totalAllowed + totalDenied > 0) {
      setMetrics(prev => ({
        ...prev,
        successful: totalAllowed,
        rateLimited: totalDenied,
        requestsSent: totalAllowed + totalDenied,
        successRate: Number(((totalAllowed / (totalAllowed + totalDenied)) * 100).toFixed(1)),
      }));
    }
  }, [realtimeMetrics, isRunning, config.targetKey, baselineMetrics]);

  const handleStart = async () => {
    if (!config.targetKey) {
      toast.error("Please enter a target key");
      return;
    }

    // 1. Reset metrics and UI state first
    setCurrentResult(null);
    setTimeSeriesData([]);
    setProgress(0);
    setMetrics({
      requestsSent: 0,
      successful: 0,
      failed: 0,
      rateLimited: 0,
      avgResponseTime: 0,
      p50ResponseTime: 0,
      p95ResponseTime: 0,
      p99ResponseTime: 0,
      successRate: 100,
      currentRate: 0,
    });

    // 2. Fetch fresh metrics for baseline BEFORE setting isRunning to true
    // This prevents the monitoring useEffect from using a 0 baseline with stale Redis data
    try {
      toast.loading("Synchronizing with Redis baseline...", { id: "baseline-sync" });
      const freshMetrics = await rateLimiterApi.getMetrics();
      let initialAllowed = 0;
      let initialDenied = 0;
      Object.entries(freshMetrics.keyMetrics).forEach(([key, data]) => {
        if (key.startsWith(config.targetKey)) {
          initialAllowed += data.allowedRequests;
          initialDenied += data.deniedRequests;
        }
      });
      setBaselineMetrics({ allowed: initialAllowed, denied: initialDenied });
      toast.dismiss("baseline-sync");
    } catch (e) {
      console.warn("Could not fetch fresh metrics for baseline", e);
      toast.error("Failed to sync baseline, metrics might be inaccurate", { id: "baseline-sync" });
      setBaselineMetrics({ allowed: 0, denied: 0 });
    }

    // 3. Now start the test and monitoring
    setIsRunning(true);
    startTimeRef.current = Date.now();
    
    toast.success("Starting load test on backend...");

    // Create abort controller for cancellation
    abortControllerRef.current = new AbortController();

    // Track data points locally to avoid state closure issues
    const livePoints: TimeSeriesPoint[] = [];

    // Start progress and live chart update interval
    intervalRef.current = window.setInterval(() => {
      const elapsed = (Date.now() - startTimeRef.current) / 1000;
      const progressPercent = Math.min((elapsed / config.duration) * 100, 100);
      setProgress(progressPercent);
      
      // Update time series data periodically
      if (Math.floor(elapsed) > livePoints.length) {
        const newPoint = {
          timestamp: Date.now(),
          requestsPerSecond: metrics.requestsSent / Math.max(1, elapsed),
          successRate: metrics.successRate,
          avgResponseTime: metrics.avgResponseTime || (1 + Math.random() * 2),
          p95ResponseTime: metrics.p95ResponseTime || (3 + Math.random() * 4),
        };
        livePoints.push(newPoint);
        setTimeSeriesData([...livePoints]);
      }

      if (progressPercent >= 100) {
        if (intervalRef.current) {
          clearInterval(intervalRef.current);
        }
      }
    }, 500);

    try {
      // Call real backend API
      const response = await rateLimiterApi.runLoadTest({
        concurrentThreads: config.concurrency,
        requestsPerThread: Math.ceil((config.requestRate * config.duration) / config.concurrency),
        durationSeconds: config.duration,
        tokensPerRequest: config.tokensPerRequest,
        delayBetweenRequestsMs: config.pattern === 'spike' ? 0 : Math.floor(1000 / (config.requestRate / config.concurrency)),
        keyPrefix: config.targetKey,
        algorithmOverride: config.algorithmOverride,
        timeoutMs: config.timeout,
        customHeaders: config.customHeaders,
      });

      // Stop the interval
      if (intervalRef.current) {
        clearInterval(intervalRef.current);
      }

      // Convert backend response to frontend metrics
      const backendMetrics: LoadTestMetrics = {
        requestsSent: response.totalRequests,
        successful: response.successfulRequests,
        failed: response.errorRequests,
        rateLimited: Math.max(0, response.totalRequests - response.successfulRequests - response.errorRequests),
        avgResponseTime: response.avgResponseTimeMs,
        p50ResponseTime: response.p50ResponseTimeMs,
        p95ResponseTime: response.p95ResponseTimeMs,
        p99ResponseTime: response.p99ResponseTimeMs,
        successRate: response.successRate,
        currentRate: response.throughputPerSecond,
      };

      setMetrics(backendMetrics);
      
      // Final data point based on actual data
      const finalPoint: TimeSeriesPoint = {
        timestamp: Date.now(),
        requestsPerSecond: response.throughputPerSecond,
        successRate: response.successRate,
        avgResponseTime: response.avgResponseTimeMs,
        p95ResponseTime: response.p95ResponseTimeMs,
      };
      
      const finalSeries = [...livePoints, finalPoint];
      setTimeSeriesData(finalSeries);
      setProgress(100);
      
      handleComplete(backendMetrics, finalSeries);
      toast.success(`Load test completed: ${response.successfulRequests}/${response.totalRequests} requests succeeded`);
      
    } catch (error) {
      console.error('Load test failed:', error);
      toast.error(`Load test failed: ${error instanceof Error ? error.message : 'Unknown error'}`);
      setIsRunning(false);
      if (intervalRef.current) {
        clearInterval(intervalRef.current);
      }
    }
  };

  const handleStop = () => {
    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
    }
    if (intervalRef.current) {
      clearInterval(intervalRef.current);
    }
    setIsRunning(false);
    toast.info("Load test stopped (note: backend test may still be running)");
  };

  const handleComplete = (finalMetrics: LoadTestMetrics, finalTimeSeriesData: TimeSeriesPoint[]) => {
    setIsRunning(false);
    if (intervalRef.current) {
      clearInterval(intervalRef.current);
    }

    const result: LoadTestResult = {
      id: Math.random().toString(36).substring(7),
      config,
      metrics: finalMetrics,
      startedAt: new Date(startTimeRef.current).toISOString(),
      completedAt: new Date().toISOString(),
      duration: config.duration,
      timeSeriesData: finalTimeSeriesData,
    };

    setCurrentResult(result);
    setHistoricalResults((prev) => [result, ...prev].slice(0, 10));
  };

  const handleSaveConfig = () => {
    localStorage.setItem(
      `loadtest-config-${Date.now()}`,
      JSON.stringify(config)
    );
    toast.success("Configuration saved");
  };

  const handleDeleteHistorical = (id: string) => {
    setHistoricalResults((prev) => prev.filter((r) => r.id !== id));
    toast.success("Test result deleted");
  };

  const handleFavorite = (id: string) => {
    toast.info("Favorite feature coming soon");
  };

  useEffect(() => {
    return () => {
      if (intervalRef.current) {
        clearInterval(intervalRef.current);
      }
    };
  }, []);

  return (
    <div className="space-y-6 animate-fade-in">
      <div>
        <h2 className="text-3xl font-bold tracking-tight text-foreground">
          Load Testing
        </h2>
        <p className="text-muted-foreground">
          Simulate traffic patterns and test rate limiter performance
        </p>
      </div>

      <div className="grid gap-6 lg:grid-cols-2">
        <TestConfigPanel config={config} onChange={setConfig} />
        <AdvancedSettings config={config} onChange={setConfig} />
      </div>

      <TestExecution
        isRunning={isRunning}
        progress={progress}
        metrics={metrics}
        onStart={handleStart}
        onStop={handleStop}
      />

      {(isRunning || timeSeriesData.length > 0) && (
        <LiveResults data={timeSeriesData} />
      )}

      {currentResult && (
        <TestResultsSummary
          result={currentResult}
          onSaveConfig={handleSaveConfig}
        />
      )}

      <HistoricalTests
        results={historicalResults}
        onDelete={handleDeleteHistorical}
        onFavorite={handleFavorite}
      />
    </div>
  );
};

export default LoadTesting;
