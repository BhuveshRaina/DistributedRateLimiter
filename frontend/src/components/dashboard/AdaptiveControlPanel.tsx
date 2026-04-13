import { useState } from "react";
import { Card } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Slider } from "@/components/ui/slider";
import { Badge } from "@/components/ui/badge";
import { Switch } from "@/components/ui/switch";
import { Brain, Zap, AlertTriangle, RefreshCw, FlaskConical } from "lucide-react";
import { rateLimiterApi } from "@/services/rateLimiterApi";
import { toast } from "sonner";

export const AdaptiveControlPanel = () => {
  const [cpu, setCpu] = useState(0);
  const [latency, setLatency] = useState(0);
  const [errorRate, setErrorRate] = useState(0);
  const [isMocking, setIsMocking] = useState(false);
  
  const [chaosDelay, setChaosDelay] = useState(0);
  const [chaosFail, setChaosFail] = useState(false);
  const [isLoading, setIsLoading] = useState(false);

  const handleApplyMock = async () => {
    setIsLoading(true);
    try {
      // Direct fetch call since we haven't added this to rateLimiterApi yet
      const response = await fetch("http://localhost:8080/admin/adaptive-test/mock-health", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ cpu: cpu / 100, latency, errorRate: errorRate / 100 }),
      });
      
      if (response.ok) {
        setIsMocking(true);
        toast.success("Mock health applied to AIMD engine");
      }
    } catch (error) {
      toast.error("Failed to apply mock health");
    } finally {
      setIsLoading(false);
    }
  };

  const handleClearMock = async () => {
    setIsLoading(true);
    try {
      const response = await fetch("http://localhost:8080/admin/adaptive-test/mock-health", {
        method: "DELETE",
      });
      
      if (response.ok) {
        setIsMocking(false);
        setCpu(0);
        setLatency(0);
        setErrorRate(0);
        toast.info("Returned to real system metrics");
      }
    } catch (error) {
      toast.error("Failed to clear mock health");
    } finally {
      setIsLoading(false);
    }
  };

  const handleTriggerChaos = async () => {
    setIsLoading(true);
    try {
      const response = await fetch(`http://localhost:8082/api/chaos/work?delayMs=${chaosDelay}&fail=${chaosFail}`);
      if (response.ok) {
        toast.warning(`Chaos triggered: ${chaosDelay}ms delay`);
      } else {
        toast.error("Chaos request failed (Simulated Error)");
      }
    } catch (error) {
      toast.error("Failed to contact Chaos API");
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <Card className="p-6 shadow-elevated border-primary/20 bg-gradient-to-br from-card to-primary/5">
      <div className="flex items-center justify-between mb-6">
        <div className="flex items-center gap-2">
          <FlaskConical className="h-5 w-5 text-primary" />
          <h3 className="text-lg font-semibold">Adaptive Test Lab</h3>
        </div>
        {isMocking && (
          <Badge variant="destructive" className="animate-pulse">
            MOCK MODE ACTIVE
          </Badge>
        )}
      </div>

      <div className="grid gap-8 md:grid-cols-2">
        {/* Mock Hardware Section */}
        <div className="space-y-4">
          <div className="flex items-center gap-2 text-sm font-medium text-muted-foreground mb-2">
            <Brain className="h-4 w-4" />
            Hardware Simulation (Manager)
          </div>
          
          <div className="space-y-6">
            <div className="space-y-2">
              <div className="flex justify-between">
                <Label>System CPU Usage ({cpu}%)</Label>
                <span className={cpu > 60 ? "text-destructive" : "text-primary"}>{cpu > 60 ? "Stress" : "Healthy"}</span>
              </div>
              <Slider value={[cpu]} onValueChange={(v) => setCpu(v[0])} max={100} step={1} />
            </div>

            <div className="space-y-2">
              <div className="flex justify-between">
                <Label>P95 Latency ({latency}ms)</Label>
                <span className={latency > 100 ? "text-destructive" : "text-primary"}>{latency > 100 ? "High" : "Low"}</span>
              </div>
              <Slider value={[latency]} onValueChange={(v) => setLatency(v[0])} max={500} step={5} />
            </div>

            <div className="flex gap-2">
              <Button 
                onClick={handleApplyMock} 
                disabled={isLoading} 
                className="flex-1"
                variant={isMocking ? "outline" : "default"}
              >
                <Zap className="mr-2 h-4 w-4" />
                Apply Mock
              </Button>
              {isMocking && (
                <Button onClick={handleClearMock} variant="ghost" disabled={isLoading}>
                  <RefreshCw className="mr-2 h-4 w-4" />
                  Reset
                </Button>
              )}
            </div>
          </div>
        </div>

        {/* Chaos/Traffic Section */}
        <div className="space-y-4 border-l pl-6 border-border">
          <div className="flex items-center gap-2 text-sm font-medium text-muted-foreground mb-2">
            <Zap className="h-4 w-4" />
            Backend Chaos (Worker)
          </div>

          <div className="space-y-6">
            <div className="space-y-2">
              <Label>Simulated Work Delay (ms)</Label>
              <Input 
                type="number" 
                value={chaosDelay} 
                onChange={(e) => setChaosDelay(parseInt(e.target.value))} 
                placeholder="e.g. 1500"
              />
            </div>

            <div className="flex items-center justify-between space-x-2">
              <Label htmlFor="chaos-fail">Force 500 Errors</Label>
              <Switch id="chaos-fail" checked={chaosFail} onCheckedChange={setChaosFail} />
            </div>

            <Button 
              onClick={handleTriggerChaos} 
              disabled={isLoading} 
              variant="destructive" 
              className="w-full"
            >
              <AlertTriangle className="mr-2 h-4 w-4" />
              Trigger Chaos Request
            </Button>
            
            <p className="text-[10px] text-muted-foreground italic">
              Note: Hit this endpoint repeatedly to affect the P95 latency metrics.
            </p>
          </div>
        </div>
      </div>
    </Card>
  );
};
