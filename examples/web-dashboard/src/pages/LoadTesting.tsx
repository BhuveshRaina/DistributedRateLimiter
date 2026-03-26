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
  const [baselineMetrics, setBaselineMetrics] = useState<{ allowed: number; denied: number } | null>(null);

  const abortControllerRef = useRef<AbortController | null>(null);
  const intervalRef = useRef<number | null>(null);
  const startTimeRef = useRef<number>(0);
  const metricsRef = useRef<LoadTestMetrics>(metrics);
  const lastGraphMetricsRef = useRef({ successful: 0, rateLimited: 0, timestamp: 0 });

  // Synchronize with global metrics during test for distributed consistency
  useEffect(() => {
    // CRITICAL: Do not monitor until we are running AND have a valid, confirmed baseline
    if (!isRunning || !realtimeMetrics || !config.targetKey || baselineMetrics === null) return;

    // Sum up metrics for all threads (e.g., user:123:1, user:123:2, etc.)
    let currentAllowed = 0;
    let currentDenied = 0;
    
    Object.entries(realtimeMetrics.keyMetrics).forEach(([key, data]) => {
      if (key.startsWith(config.targetKey)) {
        currentAllowed += data.allowedRequests;
        currentDenied += data.deniedRequests;
      }
    });

    // Detect Redis reset (metrics cleared by backend)
    if (currentAllowed < baselineMetrics.allowed || currentDenied < baselineMetrics.denied) {
      setBaselineMetrics({ allowed: 0, denied: 0 });
      return;
    }

    // Calculate delta from baseline
    const totalAllowed = Math.max(0, currentAllowed - baselineMetrics.allowed);
    const totalDenied = Math.max(0, currentDenied - baselineMetrics.denied);
    const totalRequests = totalAllowed + totalDenied;

    if (totalRequests > 0) {
      const newMetrics = {
        ...metricsRef.current,
        successful: totalAllowed,
        rateLimited: totalDenied,
        requestsSent: totalRequests,
        successRate: Number(((totalAllowed / totalRequests) * 100).toFixed(1)),
      };
      
      setMetrics(newMetrics);
      metricsRef.current = newMetrics;

      // Update graph points when new metrics arrive
      const now = Date.now();
      const elapsed = (now - startTimeRef.current) / 1000;
      const timeDelta = (now - lastGraphMetricsRef.current.timestamp) / 1000;

      // Add a point if at least 800ms has passed and elapsed > 0
      if (timeDelta >= 0.8 && elapsed > 0) {
        // Calculate instantaneous RPS using deltas for real-time throughput
        const successDiff = totalAllowed - lastGraphMetricsRef.current.successful;
        const limitedDiff = totalDenied - lastGraphMetricsRef.current.rateLimited;
        const totalDiff = successDiff + limitedDiff;
        const instantRps = totalDiff / timeDelta;

        // Use cumulative Success Rate to match the stable trend (e.g., 50 -> 40 -> 36)
        const overallSuccessRate = (totalAllowed / totalRequests) * 100;

        setTimeSeriesData(prev => {
          // Calculate a 3-point moving average for smoother "live" display
          const recentPoints = prev.slice(-2);
          const smoothedRps = recentPoints.length > 0
            ? (recentPoints.reduce((sum, p) => sum + p.requestsPerSecond, 0) + instantRps) / (recentPoints.length + 1)
            : instantRps;

          return [
            ...prev,
            {
              timestamp: Math.round(elapsed),
              requestsPerSecond: Number(smoothedRps.toFixed(1)),
              successRate: Number(overallSuccessRate.toFixed(1)),
              avgResponseTime: 0,
              p95ResponseTime: 0,
            }
          ].slice(-60);
        });

        lastGraphMetricsRef.current = {
          successful: totalAllowed,
          rateLimited: totalDenied,
          timestamp: now
        };
      }
    }
  }, [realtimeMetrics, isRunning, config.targetKey, baselineMetrics]);

  const handleStart = async () => {
    if (!config.targetKey) {
      toast.error("Please enter a target key");
      return;
    }

    // 1. Reset metrics and UI state first
    const initialMetrics = {
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
    };
    setCurrentResult(null);
    
    // Start with a 0,0 point for a clean graph
    setTimeSeriesData([{
      timestamp: 0,
      requestsPerSecond: 0,
      successRate: 100,
      avgResponseTime: 0,
      p95ResponseTime: 0,
    }]);
    
    setProgress(0);
    setMetrics(initialMetrics);
    metricsRef.current = initialMetrics;
    lastGraphMetricsRef.current = { successful: 0, rateLimited: 0, timestamp: Date.now() };
    
    // 2. Capture actual current metrics as baseline
    let initialAllowed = 0;
    let initialDenied = 0;
    if (realtimeMetrics) {
      Object.entries(realtimeMetrics.keyMetrics).forEach(([key, data]) => {
        if (key.startsWith(config.targetKey)) {
          initialAllowed += data.allowedRequests;
          initialDenied += data.deniedRequests;
        }
      });
    }
    setBaselineMetrics({ allowed: initialAllowed, denied: initialDenied });
    
    toast.success("Starting load test on backend...");

    // 3. Now start the test and monitoring
    setIsRunning(true);
    startTimeRef.current = Date.now();
    lastGraphMetricsRef.current.timestamp = startTimeRef.current;

    // Create abort controller for cancellation
    abortControllerRef.current = new AbortController();

    // Start progress interval only (graph is updated via useEffect)
    intervalRef.current = window.setInterval(() => {
      const elapsed = (Date.now() - startTimeRef.current) / 1000;
      const progressPercent = Math.min((elapsed / config.duration) * 100, 100);
      setProgress(progressPercent);
      
      if (progressPercent >= 100) {
        if (intervalRef.current) {
          clearInterval(intervalRef.current);
        }
      }
    }, 200);

    try {
      // Call real backend API
      const response = await rateLimiterApi.runLoadTest({
        concurrentThreads: config.concurrency,
        // Now treating requestRate as 'Per Thread'
        requestsPerThread: Math.ceil(config.requestRate * config.duration),
        durationSeconds: config.duration,
        tokensPerRequest: config.tokensPerRequest,
        // Delay is now calculated directly from the per-thread rate
        delayBetweenRequestsMs: config.pattern === 'spike' ? 0 : Math.floor(1000 / config.requestRate),
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
      metricsRef.current = backendMetrics;
      
      // Update the final series data
      const finalPoint: TimeSeriesPoint = {
        timestamp: config.duration,
        requestsPerSecond: response.throughputPerSecond,
        successRate: response.successRate,
        avgResponseTime: 0,
        p95ResponseTime: 0,
      };
      
      // Get the existing series data from state using a functional update pattern
      // to ensure we have the latest points captured by the useEffect
      setTimeSeriesData(prev => {
        const finalSeries = [...prev, finalPoint];
        handleComplete(backendMetrics, finalSeries);
        return finalSeries;
      });
      
      setProgress(100);
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
