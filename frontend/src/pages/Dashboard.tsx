import { useEffect, useState, useRef } from "react";
import { Card } from "@/components/ui/card";
import { Activity, TrendingUp, PieChart, Sliders } from "lucide-react";
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  Legend,
} from "recharts";
import { StatCard } from "@/components/dashboard/StatCard";
import { ActivityFeed } from "@/components/dashboard/ActivityFeed";
import { AlgorithmCard } from "@/components/dashboard/AlgorithmCard";
import { AdaptiveStatusCard } from "@/components/dashboard/AdaptiveStatusCard";
import { DashboardLoadingSkeleton } from "@/components/LoadingState";
import { ApiHealthCheck } from "@/components/ApiHealthCheck";
import { useApp } from "@/contexts/AppContext";
import { rateLimiterApi } from "@/services/rateLimiterApi";
import { useKeyboardShortcuts, dashboardShortcuts } from "@/hooks/useKeyboardShortcuts";

interface TimeSeriesData {
  time: string;
  allowed: number;
  rejected: number;
  total: number;
}

interface AlgorithmMetric {
  name: string;
  displayName: string;
  activeKeys: number;
  avgResponseTime: number;
  successRate: number;
  requestsPerSecond: number;
}

const ALGO_DISPLAY_NAMES: Record<string, string> = {
  'TOKEN_BUCKET': 'Token Bucket',
  'SLIDING_WINDOW': 'Sliding Window',
  'FIXED_WINDOW': 'Fixed Window',
  'LEAKY_BUCKET': 'Leaky Bucket',
  'COMPOSITE': 'Composite',
};

const Dashboard = () => {
  const { realtimeMetrics, isConnected } = useApp();
  const [loading, setLoading] = useState(true);
  const [activeKeys, setActiveKeys] = useState(0);
  const [requestsPerSecond, setRequestsPerSecond] = useState<string | number>(0);
  const [successRate, setSuccessRate] = useState<string | number>(100);
  const [algorithmMetrics, setAlgorithmMetrics] = useState<AlgorithmMetric[]>([]);
  const [activities, setActivities] = useState<ActivityEvent[]>([]);
  const [timeSeriesData, setTimeSeriesData] = useState<TimeSeriesData[]>([]);

  // Use Ref for previous metrics to prevent flickering and unnecessary re-renders
  const prevMetricsRef = useRef<{ 
    allowed: number; 
    denied: number; 
    timestamp: number;
    perAlgo: Record<string, { allowed: number; denied: number }>;
  } | null>(null);

  // Enable keyboard shortcuts
  useKeyboardShortcuts(dashboardShortcuts);

  // Initial data load
  useEffect(() => {
    const loadInitialData = async () => {
      try {
        const [metricsData, keysData] = await Promise.all([
          rateLimiterApi.getMetrics(),
          rateLimiterApi.getActiveKeys()
        ]);

        const now = new Date();
        const totalAllowed = metricsData.totalAllowedRequests;
        const totalDenied = metricsData.totalDeniedRequests;

        setTimeSeriesData([{
          time: now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
          allowed: totalAllowed,
          rejected: totalDenied,
          total: totalAllowed + totalDenied,
        }]);

        const perAlgo: Record<string, { allowed: number; denied: number }> = {};
        Object.entries(metricsData.perAlgorithmMetrics || {}).forEach(([name, data]) => {
          perAlgo[name] = { allowed: data.allowedRequests, denied: data.deniedRequests };
        });

        prevMetricsRef.current = { 
          allowed: totalAllowed, 
          denied: totalDenied, 
          timestamp: Date.now(),
          perAlgo
        };
        setLoading(false);
      } catch (error) {
        console.error('Failed to load dashboard data:', error);
        setLoading(false);
      }
    };
    loadInitialData();
  }, []);

  // Update metrics from real-time polling (AppContext polls /metrics every 1s)
  useEffect(() => {
    if (!realtimeMetrics) return;

    const totalRequests = realtimeMetrics.totalAllowedRequests + realtimeMetrics.totalDeniedRequests;
    const currentSuccessRate = totalRequests > 0 
      ? (realtimeMetrics.totalAllowedRequests / totalRequests * 100).toFixed(1)
      : "100.0";

    const currentActiveKeys = Object.keys(realtimeMetrics.keyMetrics).length;

    // Calculate requests per second and update time series
    const now = Date.now();
    if (prevMetricsRef.current) {
      const timeDiff = (now - prevMetricsRef.current.timestamp) / 1000; // seconds

      // Update at most once per 500ms to keep it smooth
      if (timeDiff >= 0.5) {
        const allowedDiff = realtimeMetrics.totalAllowedRequests - prevMetricsRef.current.allowed;
        const deniedDiff = realtimeMetrics.totalDeniedRequests - prevMetricsRef.current.denied;
        const totalDiff = allowedDiff + deniedDiff;

        const rps = (totalDiff / timeDiff).toFixed(1);
        const allowedRps = (allowedDiff / timeDiff).toFixed(1);
        const deniedRps = (deniedDiff / timeDiff).toFixed(1);

        setRequestsPerSecond(rps);

        // Update time series data
        const timeStr = new Date(now).toLocaleTimeString([], { minute: '2-digit', second: '2-digit' });
        setTimeSeriesData((prev) => {
          const newPoint = {
            time: timeStr,
            allowed: parseFloat(allowedRps),
            rejected: parseFloat(deniedRps),
            total: parseFloat(rps),
          };
          return [...prev, newPoint].slice(-300);
        });

        // Update algorithm metrics with individual live RPS
        if (realtimeMetrics.perAlgorithmMetrics) {
          const metrics: AlgorithmMetric[] = Object.entries(realtimeMetrics.perAlgorithmMetrics)
            .filter(([_, data]) => (data.allowedRequests + data.deniedRequests) > 0)
            .map(([name, data]) => {
              const prev = prevMetricsRef.current?.perAlgo[name] || { allowed: 0, denied: 0 };
              const algoTotalDiff = (data.allowedRequests + data.deniedRequests) - (prev.allowed + prev.denied);
              const algoRps = parseFloat((algoTotalDiff / timeDiff).toFixed(1));

              const total = data.allowedRequests + data.deniedRequests || 1;
              return {
                name,
                displayName: ALGO_DISPLAY_NAMES[name] || name,
                activeKeys: 0, // Updated by fetchAlgorithmMetrics
                avgResponseTime: parseFloat((data.totalProcessingTimeMs / total).toFixed(2)),
                successRate: parseFloat(((data.allowedRequests / total) * 100).toFixed(1)),
                requestsPerSecond: algoRps
              };
            });

          if (metrics.length > 0) {
            setAlgorithmMetrics(prev => {
              return metrics.map(m => {
                const existing = prev.find(p => p.name === m.name);
                return { ...m, activeKeys: existing?.activeKeys || 0 };
              });
            });
          }
        }

        // Update prevMetricsRef for next calculation
        const currentPerAlgo: Record<string, { allowed: number; denied: number }> = {};
        Object.entries(realtimeMetrics.perAlgorithmMetrics || {}).forEach(([name, data]) => {
          currentPerAlgo[name] = { allowed: data.allowedRequests, denied: data.deniedRequests };
        });

        prevMetricsRef.current = {
          allowed: realtimeMetrics.totalAllowedRequests,
          denied: realtimeMetrics.totalDeniedRequests,
          timestamp: now,
          perAlgo: currentPerAlgo
        };
      }
    } else {
      // First run initialization if initial load missed it
      prevMetricsRef.current = {
        allowed: realtimeMetrics.totalAllowedRequests,
        denied: realtimeMetrics.totalDeniedRequests,
        timestamp: now,
        perAlgo: {}
      };
    }

    setActiveKeys(currentActiveKeys);
    setSuccessRate(currentSuccessRate);

    // Update activity events
    if (realtimeMetrics.recentEvents) {
      const newActivities: ActivityEvent[] = realtimeMetrics.recentEvents.map(event => ({
        id: event.id,
        timestamp: new Date(event.timestamp).toISOString(),
        key: event.key,
        algorithm: ALGO_DISPLAY_NAMES[event.algorithm] || event.algorithm,
        status: event.allowed ? "allowed" : "rejected",
        tokensUsed: event.tokens,
      }));

      setActivities(newActivities.sort((a, b) => 
        new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime()
      ));
    }

  }, [realtimeMetrics]);

  // Fetch algorithm keys distribution
  useEffect(() => {
    const fetchAlgorithmMetrics = async () => {
      try {
        const keysData = await rateLimiterApi.getActiveKeys();
        const algorithmGroups: Record<string, number> = {};

        keysData.keys.forEach(key => {
          const algo = key.algorithm || 'TOKEN_BUCKET';
          algorithmGroups[algo] = (algorithmGroups[algo] || 0) + 1;
        });

        setAlgorithmMetrics(prev => prev.map(m => ({
          ...m,
          activeKeys: algorithmGroups[m.name] || 0
        })));
      } catch (error) {
        console.error('Failed to fetch algorithm metrics:', error);
      }
    };

    fetchAlgorithmMetrics();
    const interval = setInterval(fetchAlgorithmMetrics, 30000);
    return () => clearInterval(interval);
  }, []);

  if (loading) {
    return <DashboardLoadingSkeleton />;
  }

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-3xl font-bold tracking-tight text-foreground">Dashboard</h2>
        <p className="text-muted-foreground">
          Real-time monitoring of your rate limiter performance
        </p>
      </div>

      <ApiHealthCheck />

      {/* Top Stats Row */}
      <div className="grid gap-6 md:grid-cols-2 lg:grid-cols-4">
        <StatCard
          title="Active Keys"
          value={activeKeys}
          icon={Activity}
          trend={isConnected ? { value: "Live", isPositive: true } : undefined}
        />

        <StatCard
          title="Requests/Second"
          value={requestsPerSecond}
          icon={TrendingUp}
          trend={isConnected ? { value: "Live", isPositive: true } : undefined}
        />

        <StatCard
          title="Success Rate"
          value={`${successRate}%`}
          icon={PieChart}
          trend={parseFloat(successRate.toString()) >= 95 ? { value: "Healthy", isPositive: true } : { value: "Warning", isPositive: false }}
        />

        <StatCard
          title="Algorithms"
          value={algorithmMetrics.length}
          icon={Sliders}
        />
      </div>

      {/* Algorithm Performance Cards */}
      <div>
        <h3 className="mb-4 text-xl font-semibold text-foreground">Algorithm Performance</h3>
        <div className="grid gap-6 md:grid-cols-2 lg:grid-cols-4">
          {algorithmMetrics.map((algo) => (
            <AlgorithmCard
              key={algo.name}
              name={algo.displayName}
              activeKeys={algo.activeKeys}
              avgResponseTime={algo.avgResponseTime}
              successRate={algo.successRate}
              requestsPerSecond={algo.requestsPerSecond}
            />
          ))}
        </div>
      </div>
      {/* Bottom Section: Activity Feed and Adaptive Status */}
      <div className="grid gap-6 lg:grid-cols-3">
        <div className="lg:col-span-2">
          <ActivityFeed events={activities} />
        </div>
        <div>
          <AdaptiveStatusCard />
        </div>
      </div>
    </div>
  );
};

export default Dashboard;
