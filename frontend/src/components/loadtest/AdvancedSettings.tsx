import { Card } from "@/components/ui/card";
import { Label } from "@/components/ui/label";
import { Input } from "@/components/ui/input";
import { Slider } from "@/components/ui/slider";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { LoadTestConfig } from "@/types/loadTesting";

interface AdvancedSettingsProps {
  config: LoadTestConfig;
  onChange: (config: LoadTestConfig) => void;
}

export const AdvancedSettings = ({ config, onChange }: AdvancedSettingsProps) => {
  return (
    <Card className="shadow-elevated backdrop-blur-sm bg-gradient-to-br from-card to-card/50 p-6">
      <div className="mb-6">
        <h3 className="text-lg font-semibold text-foreground">
          Advanced Settings
        </h3>
      </div>

      <div className="space-y-6">
        <div className="space-y-3">
          <div className="flex items-center justify-between">
            <Label>Tokens per Request</Label>
            <span className="text-sm font-medium text-foreground">
              {config.tokensPerRequest}
            </span>
          </div>
          <Slider
            value={[config.tokensPerRequest]}
            onValueChange={([value]) =>
              onChange({ ...config, tokensPerRequest: value })
            }
            min={1}
            max={10}
            step={1}
            className="py-4"
          />
        </div>

        <div className="space-y-3">
          <div className="flex items-center justify-between">
            <Label>Request Timeout (ms)</Label>
            <span className="text-sm font-medium text-foreground">
              {config.timeout}
            </span>
          </div>
          <Slider
            value={[config.timeout]}
            onValueChange={([value]) => onChange({ ...config, timeout: value })}
            min={100}
            max={10000}
            step={100}
            className="py-4"
          />
        </div>

        <div className="space-y-2">
          <Label htmlFor="custom-headers">Custom Headers (JSON)</Label>
          <Input
            id="custom-headers"
            placeholder='{"X-Custom-Header": "value"}'
            defaultValue={JSON.stringify(config.customHeaders || {})}
            onBlur={(e) => {
              try {
                const headers = JSON.parse(e.target.value || "{}");
                onChange({ ...config, customHeaders: headers });
              } catch {
                // Invalid JSON, ignore
              }
            }}
          />
        </div>
      </div>
    </Card>
  );
};
