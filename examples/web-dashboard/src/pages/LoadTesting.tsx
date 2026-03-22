import { useState, useEffect, useRef } from "react";
import { TestConfigPanel } from "@/components/loadtest/TestConfigPanel";
import { AdvancedSettings } from "@/components/loadtest/AdvancedSettings";
import { TestExecution } from "@/components/loadtest/TestExecution";
import { LiveResults } from "@/components/loadtest/LiveResults";
import { TestResultsSummary } from "@/components/loadtest/TestResultsSummary";
import { HistoricalTests } from "@/components/loadtest/HistoricalTests";
import { rateLimiterApi } from "@/services/rateLimiterApi";
import { toast } from "sonner";
import {
  LoadTestConfig,
  LoadTestMetrics,
  LoadTestResult,
  TimeSeriesPoint,
} from "@/types/loadTesting";

const LoadTesting = () => {
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

  const abortControllerRef = useRef<AbortController | null>(null);
  const intervalRef = useRef<number | null>(null);
  const startTimeRef = useRef<number>(0);

  const handleStart = async () => {
    if (!config.targetKey) {
      toast.error("Please enter a target key");
      return;
    }

    setIsRunning(true);
    setCurrentResult(null);
    setTimeSeriesData([]);
    setProgress(0);
    startTimeRef.current = Date.now();
    
    // Reset metrics
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
    
    toast.success("Starting load test on backend...");

    // Fetch actual server config for accurate simulation
    let serverRefillRate = 2;
    let serverCapacity = 10;
    try {
      const serverConfig = await rateLimiterApi.getConfig();
      serverRefillRate = serverConfig.refillRate;
      serverCapacity = serverConfig.capacity;
      
      // Check for key-specific override
      if (serverConfig.keyConfigs && serverConfig.keyConfigs[config.targetKey]) {
        serverRefillRate = serverConfig.keyConfigs[config.targetKey].refillRate;
        serverCapacity = serverConfig.keyConfigs[config.targetKey].capacity;
      }
    } catch (e) {
      console.warn("Could not fetch server config, using defaults for simulation", e);
    }

    // Create abort controller for cancellation
    abortControllerRef.current = new AbortController();

    // Track simulation data points locally to avoid state closure issues
    const livePoints: TimeSeriesPoint[] = [];

    // Start progress simulation
    intervalRef.current = window.setInterval(() => {
      const elapsed = (Date.now() - startTimeRef.current) / 1000;
      const progressPercent = Math.min((elapsed / config.duration) * 100, 100);
      setProgress(progressPercent);
      
      // Calculate estimated metrics for live display
      const totalEstimated = Math.floor(config.requestRate * elapsed);
      // Accurate estimation: (capacity + refill) * number of concurrent buckets / tokens per request
      const tokensAllowedPerBucket = serverCapacity + Math.floor(serverRefillRate * elapsed);
      const totalTokensAllowed = tokensAllowedPerBucket * config.concurrency;
      const successfulEstimated = Math.min(totalEstimated, Math.floor(totalTokensAllowed / config.tokensPerRequest));
      
      setMetrics(prev => ({
        ...prev,
        requestsSent: totalEstimated,
        successful: successfulEstimated,
        rateLimited: Math.max(0, totalEstimated - successfulEstimated),
        currentRate: Number((config.requestRate * (0.98 + Math.random() * 0.04)).toFixed(1)),
        successRate: Number(((successfulEstimated / Math.max(1, totalEstimated)) * 100).toFixed(1)),
        avgResponseTime: Number((1 + Math.random() * 2).toFixed(1)),
      }));
      
      // Add periodic data points for the graph during simulation
      if (Math.floor(elapsed) > livePoints.length) {
        const newPoint = {
          timestamp: Date.now(),
          requestsPerSecond: config.requestRate * (0.95 + Math.random() * 0.1),
          successRate: Number(((successfulEstimated / Math.max(1, totalEstimated)) * 100).toFixed(1)),
          avgResponseTime: Number((1 + Math.random() * 2).toFixed(1)),
          p95ResponseTime: Number((3 + Math.random() * 4).toFixed(1)),
        };
        livePoints.push(newPoint);
        setTimeSeriesData([...livePoints]);
      }

      if (progressPercent >= 100) {
        if (intervalRef.current) {
          clearInterval(intervalRef.current);
        }
      }
    }, 100);

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

      // Stop the simulation interval
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
