import { useState, useEffect, useCallback } from "react";
import { Card } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Progress } from "@/components/ui/progress";
import { Switch } from "@/components/ui/switch";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import {
  Brain,
  Activity,
  Shield,
  Zap,
  Settings,
  RefreshCw,
  AlertTriangle,
  CheckCircle,
  Info,
  Edit3,
  X,
  Plus,
  Trash2,
  Lock,
  Globe,
} from "lucide-react";
import { toast } from "sonner";
import { rateLimiterApi } from "@/services/rateLimiterApi";
import type { AdaptiveStatus, AdaptiveConfig, AdaptiveOverrideRequest } from "@/types/adaptive";
import { useApp } from "@/contexts/AppContext";

const Adaptive = () => {
  const { isConnected } = useApp();
  const [loading, setLoading] = useState(true);
  const [config, setConfig] = useState<AdaptiveConfig | null>(null);
  const [adaptiveStatuses, setAdaptiveStatuses] = useState<AdaptiveStatus[]>([]);
  const [adaptiveTargets, setAdaptiveTargets] = useState<{ target: string; isPattern: boolean }[]>([]);
  const [refreshing, setRefreshing] = useState(false);
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const [overrideDialogOpen, setOverrideDialogOpen] = useState(false);
  const [addTargetDialogOpen, setAddTargetDialogOpen] = useState(false);
  const [newTarget, setNewTarget] = useState({ 
    target: "", 
    isPattern: false,
    initialCapacity: 10,
    initialRefillRate: 2
  });
  const [overrideForm, setOverrideForm] = useState<AdaptiveOverrideRequest>({
    capacity: 100,
    refillRate: 10,
    reason: "",
  });

  const loadData = useCallback(async () => {
    try {
      const [configData, statusesData, targetsData] = await Promise.all([
        rateLimiterApi.getAdaptiveConfig(),
        rateLimiterApi.getAdaptiveStatusForAllKeys(),
        rateLimiterApi.getAdaptiveTargets(),
      ]);
      setConfig(configData);
      setAdaptiveStatuses(statusesData);
      setAdaptiveTargets(targetsData);
    } catch (error) {
      console.error("Failed to load adaptive data:", error);
      toast.error("Failed to load adaptive rate limiting data");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadData();
    // Auto-refresh every 30 seconds
    const interval = setInterval(loadData, 30000);
    return () => clearInterval(interval);
  }, [loadData]);

  const handleRefresh = async () => {
    setRefreshing(true);
    await loadData();
    setRefreshing(false);
    toast.success("Data refreshed");
  };

  const handleAddTarget = async () => {
    if (!newTarget.target) return;
    try {
      await rateLimiterApi.addAdaptiveTarget(newTarget);
      toast.success(`Added adaptive target: ${newTarget.target}`);
      setAddTargetDialogOpen(false);
      setNewTarget({ target: "", isPattern: false, initialCapacity: 10, initialRefillRate: 2 });
      await loadData();
    } catch (error) {
      console.error("Failed to add target:", error);
      toast.error("Failed to add adaptive target");
    }
  };

  const handleRemoveTarget = async (target: string) => {
    try {
      await rateLimiterApi.removeAdaptiveTarget(target);
      toast.success(`Removed adaptive target: ${target}`);
      await loadData();
    } catch (error) {
      console.error("Failed to remove target:", error);
      toast.error("Failed to remove adaptive target");
    }
  };

  const handleSetOverride = async () => {
    if (!selectedKey) return;
    
    try {
      await rateLimiterApi.setAdaptiveOverride(selectedKey, overrideForm);
      toast.success(`Override set for ${selectedKey}`);
      setOverrideDialogOpen(false);
      setSelectedKey(null);
      setOverrideForm({ capacity: 100, refillRate: 10, reason: "" });
      await loadData();
    } catch (error) {
      console.error("Failed to set override:", error);
      toast.error("Failed to set override");
    }
  };

  const handleRemoveOverride = async (key: string) => {
    try {
      await rateLimiterApi.removeAdaptiveOverride(key);
      toast.success(`Override removed for ${key}`);
      await loadData();
    } catch (error) {
      console.error("Failed to remove override:", error);
      toast.error("Failed to remove override");
    }
  };

  const getModeColor = (mode: string) => {
    switch (mode) {
      case "ADAPTIVE":
        return "bg-green-500";
      case "LEARNING":
        return "bg-blue-500";
      case "OVERRIDE":
        return "bg-orange-500";
      default:
        return "bg-gray-500";
    }
  };

  const getModeIcon = (mode: string) => {
    switch (mode) {
      case "ADAPTIVE":
        return <Brain className="h-4 w-4" />;
      case "LEARNING":
        return <Activity className="h-4 w-4" />;
      case "OVERRIDE":
        return <Shield className="h-4 w-4" />;
      default:
        return <Settings className="h-4 w-4" />;
    }
  };

  const formatEvaluationInterval = (ms: number) => {
    const minutes = Math.floor(ms / 60000);
    if (minutes >= 60) {
      return `${Math.floor(minutes / 60)}h ${minutes % 60}m`;
    }
    return `${minutes}m`;
  };

  if (loading) {
    return (
      <div className="space-y-6 animate-fade-in">
        <div className="flex items-center justify-center h-64">
          <div className="flex items-center gap-2">
            <RefreshCw className="h-6 w-6 animate-spin text-muted-foreground" />
            <span className="text-muted-foreground">Loading adaptive rate limiting data...</span>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-6 animate-fade-in">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-3xl font-bold tracking-tight text-foreground flex items-center gap-3">
            <Brain className="h-8 w-8 text-primary" />
            Adaptive Rate Limiting
          </h2>
          <p className="text-muted-foreground">
            AIMD policy-driven automatic rate limit optimization based on traffic patterns and system metrics
          </p>
        </div>
        <Button
          onClick={handleRefresh}
          variant="outline"
          disabled={refreshing}
          className="gap-2"
        >
          <RefreshCw className={`h-4 w-4 ${refreshing ? "animate-spin" : ""}`} />
          Refresh
        </Button>
      </div>

      {/* Status Banner */}
      {config && (
        <Alert className={config.enabled ? "border-green-500/50 bg-green-500/10" : "border-yellow-500/50 bg-yellow-500/10"}>
          {config.enabled ? (
            <CheckCircle className="h-4 w-4 text-green-600" />
          ) : (
            <AlertTriangle className="h-4 w-4 text-yellow-600" />
          )}
          <AlertDescription className={config.enabled ? "text-green-900 dark:text-green-100" : "text-yellow-900 dark:text-yellow-100"}>
            {config.enabled ? (
              <>
                <strong>Adaptive Rate Limiting is ENABLED.</strong> The system is actively adjusting rate limits
                based on AIMD policies and system metrics. Evaluations occur every{" "}
                {formatEvaluationInterval(config.evaluationIntervalMs)}.
              </>
            ) : (
              <>
                <strong>Adaptive Rate Limiting is DISABLED.</strong> Set{" "}
                <code className="bg-yellow-200/50 dark:bg-yellow-800/50 px-1 rounded">
                  ratelimiter.adaptive.enabled=true
                </code>{" "}
                in application.properties to enable AIMD-driven rate limiting.
              </>
            )}
          </AlertDescription>
        </Alert>
      )}

      {/* Configuration Overview */}
      {config && (
        <Card className="p-6">
          <h3 className="text-lg font-semibold mb-4 flex items-center gap-2">
            <Settings className="h-5 w-5" />
            Configuration
          </h3>
          <div className="grid gap-4 md:grid-cols-3 lg:grid-cols-6">
            <div className="space-y-1">
              <Label className="text-xs text-muted-foreground">Status</Label>
              <div className="flex items-center gap-2">
                <Badge variant={config.enabled ? "default" : "secondary"}>
                  {config.enabled ? "Enabled" : "Disabled"}
                </Badge>
              </div>
            </div>
            <div className="space-y-1">
              <Label className="text-xs text-muted-foreground">Evaluation Interval</Label>
              <p className="font-medium">{formatEvaluationInterval(config.evaluationIntervalMs)}</p>
            </div>
            <div className="space-y-1">
              <Label className="text-xs text-muted-foreground">Min Confidence</Label>
              <p className="font-medium">{(config.minConfidenceThreshold * 100).toFixed(0)}%</p>
            </div>
            <div className="space-y-1">
              <Label className="text-xs text-muted-foreground">Max Adjustment</Label>
              <p className="font-medium">{config.maxAdjustmentFactor}x</p>
            </div>
            <div className="space-y-1">
              <Label className="text-xs text-muted-foreground">Min Capacity</Label>
              <p className="font-medium">{config.minCapacity.toLocaleString()}</p>
            </div>
            <div className="space-y-1">
              <Label className="text-xs text-muted-foreground">Max Capacity</Label>
              <p className="font-medium">{config.maxCapacity.toLocaleString()}</p>
            </div>
          </div>
        </Card>
      )}

      {/* Stats Overview */}
      <div className="grid gap-6 md:grid-cols-2">
        <Card className="p-6">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-muted-foreground">Total Managed Keys</p>
              <p className="text-3xl font-bold">{adaptiveStatuses.length}</p>
            </div>
            <div className="h-12 w-12 rounded-full bg-primary/10 flex items-center justify-center">
              <Zap className="h-6 w-6 text-primary" />
            </div>
          </div>
        </Card>
        <Card className="p-6">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-muted-foreground">Manual Overrides Active</p>
              <p className="text-3xl font-bold">
                {adaptiveStatuses.filter(s => s.adaptiveStatus.mode === "OVERRIDE").length}
              </p>
            </div>
            <div className="h-12 w-12 rounded-full bg-orange-500/10 flex items-center justify-center">
              <Shield className="h-6 w-6 text-orange-600" />
            </div>
          </div>
        </Card>
      </div>

      {/* Adaptive Target Management */}
      <Card className="p-6">
        <div className="flex items-center justify-between mb-6">
          <h3 className="text-lg font-semibold flex items-center gap-2">
            <Zap className="h-5 w-5" />
            Adaptive Targets
          </h3>
          <Dialog open={addTargetDialogOpen} onOpenChange={setAddTargetDialogOpen}>
            <DialogTrigger asChild>
              <Button className="gap-2">
                <Plus className="h-4 w-4" />
                Add Adaptive Key
              </Button>
            </DialogTrigger>
            <DialogContent>
              <DialogHeader>
                <DialogTitle>Add Adaptive Target</DialogTitle>
                <DialogDescription>
                  Define a key or pattern that should be automatically managed by the adaptive engine.
                </DialogDescription>
              </DialogHeader>
              <div className="grid gap-4 py-4">
                <div className="grid gap-2">
                  <Label htmlFor="target">Key or Pattern</Label>
                  <Input
                    id="target"
                    placeholder={newTarget.isPattern ? "e.g. user:*" : "e.g. user:123"}
                    value={newTarget.target}
                    onChange={(e) => setNewTarget({ ...newTarget, target: e.target.value })}
                  />
                </div>
                <div className="grid grid-cols-2 gap-4">
                  <div className="grid gap-2">
                    <Label htmlFor="initialCapacity">Initial Capacity</Label>
                    <Input
                      id="initialCapacity"
                      type="number"
                      value={newTarget.initialCapacity}
                      onChange={(e) => setNewTarget({ ...newTarget, initialCapacity: parseInt(e.target.value, 10) || 0 })}
                    />
                  </div>
                  <div className="grid gap-2">
                    <Label htmlFor="initialRefillRate">Initial Refill Rate</Label>
                    <Input
                      id="initialRefillRate"
                      type="number"
                      value={newTarget.initialRefillRate}
                      onChange={(e) => setNewTarget({ ...newTarget, initialRefillRate: parseInt(e.target.value, 10) || 0 })}
                    />
                  </div>
                </div>
                <div className="flex items-center justify-between space-x-2 border p-3 rounded-md bg-muted/30">
                  <div className="space-y-0.5">
                    <Label htmlFor="pattern-mode" className="font-semibold">Pattern Based</Label>
                    <p className="text-xs text-muted-foreground">
                      Use wildcards (e.g. api:*) to match multiple keys.
                    </p>
                  </div>
                  <Switch
                    id="pattern-mode"
                    checked={newTarget.isPattern}
                    onCheckedChange={(checked) => setNewTarget({ ...newTarget, isPattern: checked })}
                  />
                </div>
                {newTarget.isPattern && (
                  <Alert className="py-2 px-3">
                    <Info className="h-4 w-4" />
                    <AlertDescription className="text-xs">
                      Actual keys always take priority over matching patterns.
                    </AlertDescription>
                  </Alert>
                )}
              </div>
              <DialogFooter>
                <Button variant="outline" onClick={() => setAddTargetDialogOpen(false)}>Cancel</Button>
                <Button onClick={handleAddTarget} disabled={!newTarget.target}>Add Target</Button>
              </DialogFooter>
            </DialogContent>
          </Dialog>
        </div>

        {adaptiveTargets.length === 0 ? (
          <div className="text-center py-8 border-2 border-dashed rounded-lg">
            <Zap className="h-8 w-8 mx-auto text-muted-foreground mb-2" />
            <p className="text-muted-foreground">No explicit adaptive targets defined</p>
            <p className="text-xs text-muted-foreground">The system will still learn from active traffic.</p>
          </div>
        ) : (
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {adaptiveTargets.map((target) => (
              <div key={target.target} className="flex items-center justify-between p-3 border rounded-lg bg-card hover:shadow-sm transition-shadow">
                <div className="flex items-center gap-3 overflow-hidden">
                  <div className={`p-2 rounded-full ${target.isPattern ? 'bg-purple-500/10' : 'bg-blue-500/10'}`}>
                    {target.isPattern ? <Globe className="h-4 w-4 text-purple-600" /> : <Lock className="h-4 w-4 text-blue-600" />}
                  </div>
                  <div className="overflow-hidden">
                    <p className="font-mono text-sm font-semibold truncate" title={target.target}>{target.target}</p>
                    <p className="text-[10px] text-muted-foreground uppercase tracking-wider font-bold">
                      {target.isPattern ? 'Pattern' : 'Actual Key'}
                    </p>
                  </div>
                </div>
                <Button variant="ghost" size="icon" onClick={() => handleRemoveTarget(target.target)} className="text-muted-foreground hover:text-destructive">
                  <Trash2 className="h-4 w-4" />
                </Button>
              </div>
            ))}
          </div>
        )}
      </Card>

      {/* Keys Table */}
      <Card className="p-6">
        <div className="flex items-center justify-between mb-4">
          <h3 className="text-lg font-semibold flex items-center gap-2">
            <Activity className="h-5 w-5" />
            Active Adaptation Monitor
          </h3>
          {!isConnected && (
            <Badge variant="destructive" className="gap-1">
              <AlertTriangle className="h-3 w-3" />
              Disconnected
            </Badge>
          )}
        </div>

        {adaptiveStatuses.length === 0 ? (
          <div className="text-center py-12">
            <Info className="h-12 w-12 mx-auto text-muted-foreground mb-4" />
            <p className="text-muted-foreground mb-2">No active adaptations observed</p>
            <p className="text-sm text-muted-foreground">
              Monitoring traffic and applying AIMD decisions...
            </p>
          </div>
        ) : (
          <div className="rounded-md border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Key</TableHead>
                  <TableHead>Current Limits</TableHead>
                  <TableHead>Reasoning</TableHead>
                  <TableHead className="text-right">Actions</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {adaptiveStatuses.map((status) => {
                  const isExplicitTarget = adaptiveTargets.some(t => t.target === status.key);
                  const isPatternMatch = !isExplicitTarget && adaptiveTargets.some(t => t.isPattern && status.key.match(new RegExp('^' + t.target.replace(/\*/g, '.*') + '$')));
                  const isOverride = status.adaptiveStatus.mode === "OVERRIDE";
                  
                  return (
                    <TableRow key={status.key}>
                      <TableCell className="font-mono text-sm">
                        <div className="flex flex-col">
                          <div className="flex items-center gap-2">
                            <span className="font-semibold">{status.displayName}</span>
                            {isExplicitTarget && <Badge variant="outline" className="text-[9px] h-4 border-blue-500 text-blue-500">Target</Badge>}
                            {isPatternMatch && <Badge variant="outline" className="text-[9px] h-4 border-purple-500 text-purple-500">Matched</Badge>}
                            {isOverride && <Badge className="gap-1 bg-orange-500 text-[9px] h-4"><Shield className="h-3 w-3" />Override</Badge>}
                          </div>
                          <span className="text-[10px] text-muted-foreground truncate max-w-[150px]" title={status.key}>
                            {status.key}
                          </span>
                        </div>
                      </TableCell>
                      <TableCell>
                        <div className="text-sm">
                          <div>Capacity: <span className="font-medium">{status.currentLimits.capacity}</span></div>
                          <div>Refill: <span className="font-medium">{status.currentLimits.refillRate}/s</span></div>
                        </div>
                      </TableCell>
                      <TableCell className="max-w-xs">
                        <div className="text-sm text-muted-foreground truncate">
                          {Object.entries(status.adaptiveStatus.reasoning || {}).map(([key, value]) => (
                            <div key={key} className="truncate" title={`${key}: ${value}`}>
                              <span className="font-medium">{key}:</span> {value}
                            </div>
                          )).slice(0, 2)}
                        </div>
                      </TableCell>
                      <TableCell className="text-right">
                        <div className="flex items-center justify-end gap-2">
                          {status.adaptiveStatus.mode === "OVERRIDE" ? (
                            <Button
                              size="sm"
                              variant="outline"
                              onClick={() => handleRemoveOverride(status.key)}
                              className="gap-1"
                            >
                              <X className="h-3 w-3" />
                              Remove Override
                            </Button>
                          ) : (
                            <Dialog open={overrideDialogOpen && selectedKey === status.key} onOpenChange={(open) => {
                              setOverrideDialogOpen(open);
                              if (open) {
                                setSelectedKey(status.key);
                                setOverrideForm({
                                  capacity: status.currentLimits.capacity,
                                  refillRate: status.currentLimits.refillRate,
                                  reason: "",
                                });
                              } else {
                                setSelectedKey(null);
                              }
                            }}>
                              <DialogTrigger asChild>
                                <Button size="sm" variant="outline" className="gap-1">
                                  <Edit3 className="h-3 w-3" />
                                  Override
                                </Button>
                              </DialogTrigger>
                              <DialogContent>
                                <DialogHeader>
                                  <DialogTitle>Set Manual Override</DialogTitle>
                                  <DialogDescription>
                                    Override adaptive limits for key: <code className="bg-muted px-1 rounded">{status.key}</code>
                                  </DialogDescription>
                                </DialogHeader>
                                <div className="grid gap-4 py-4">
                                  <div className="grid gap-2">
                                    <Label htmlFor="capacity">Capacity</Label>
                                    <Input
                                      id="capacity"
                                      type="number"
                                      value={overrideForm.capacity}
                                      onChange={(e) => setOverrideForm({ ...overrideForm, capacity: parseInt(e.target.value, 10) || 0 })}
                                    />
                                  </div>
                                  <div className="grid gap-2">
                                    <Label htmlFor="refillRate">Refill Rate (per second)</Label>
                                    <Input
                                      id="refillRate"
                                      type="number"
                                      value={overrideForm.refillRate}
                                      onChange={(e) => setOverrideForm({ ...overrideForm, refillRate: parseInt(e.target.value, 10) || 0 })}
                                    />
                                  </div>
                                  <div className="grid gap-2">
                                    <Label htmlFor="reason">Reason</Label>
                                    <Textarea
                                      id="reason"
                                      placeholder="Enter reason for override..."
                                      value={overrideForm.reason}
                                      onChange={(e) => setOverrideForm({ ...overrideForm, reason: e.target.value })}
                                    />
                                  </div>
                                </div>
                                <DialogFooter>
                                  <Button variant="outline" onClick={() => setOverrideDialogOpen(false)}>
                                    Cancel
                                  </Button>
                                  <Button onClick={handleSetOverride} disabled={!overrideForm.reason}>
                                    Set Override
                                  </Button>
                                </DialogFooter>
                              </DialogContent>
                            </Dialog>
                          )}
                        </div>
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </div>
        )}
      </Card>

      {/* Help Section */}
      <Card className="p-6 bg-gradient-to-br from-card to-muted/30">
        <h3 className="text-lg font-semibold mb-4 flex items-center gap-2">
          <Info className="h-5 w-5" />
          System Information
        </h3>
        <div className="grid gap-4 md:grid-cols-2">
          <div className="flex items-start gap-3 border p-4 rounded-lg bg-background/50">
            <div className="h-10 w-10 rounded-full bg-green-500/20 flex items-center justify-center flex-shrink-0">
              <Brain className="h-5 w-5 text-green-600" />
            </div>
            <div>
              <p className="font-semibold">Automatic AIMD Policy</p>
              <p className="text-sm text-muted-foreground leading-relaxed">
                All managed keys are subject to the <strong>Additive Increase / Multiplicative Decrease</strong> policy. 
                Limits grow gradually when the system is healthy and are reduced instantly during stress.
              </p>
            </div>
          </div>
          <div className="flex items-start gap-3 border p-4 rounded-lg bg-background/50">
            <div className="h-10 w-10 rounded-full bg-orange-500/20 flex items-center justify-center flex-shrink-0">
              <Shield className="h-5 w-5 text-orange-600" />
            </div>
            <div>
              <p className="font-semibold">Manual Overrides</p>
              <p className="text-sm text-muted-foreground leading-relaxed">
                Admins can manually set specific limits for a key. This pauses the automatic AIMD 
                adjustments for that key until the override is removed.
              </p>
            </div>
          </div>
        </div>
      </Card>
    </div>
  );
};

export default Adaptive;
