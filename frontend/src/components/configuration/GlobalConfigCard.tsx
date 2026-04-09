import { useEffect } from "react";
import { Card } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Settings } from "lucide-react";
import { GlobalConfig } from "@/types/configuration";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import * as z from "zod";
import {
  Form,
  FormControl,
  FormDescription,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";

const globalConfigSchema = z.object({
  defaultCapacity: z.number().min(1).max(1000000),
  defaultRefillRate: z.number().min(1).max(100000),
  cleanupInterval: z.number().min(1).max(86400),
  algorithm: z.enum(["token-bucket", "sliding-window", "fixed-window", "leaky-bucket"]),
});

interface GlobalConfigCardProps {
  config: GlobalConfig;
  onUpdate: (config: GlobalConfig) => void;
}

export const GlobalConfigCard = ({ config, onUpdate }: GlobalConfigCardProps) => {
  const form = useForm<GlobalConfig>({
    resolver: zodResolver(globalConfigSchema),
    defaultValues: config,
  });

  const selectedAlgorithm = form.watch("algorithm");
  const isWindowBased = selectedAlgorithm === "fixed-window" || selectedAlgorithm === "sliding-window";

  // Sync form with updated config prop (e.g., after fetching from backend)
  useEffect(() => {
    form.reset(config);
  }, [config, form]);

  // Keep refillRate in sync with capacity for window-based algorithms
  useEffect(() => {
    if (isWindowBased) {
      const capacity = form.getValues("defaultCapacity");
      if (form.getValues("defaultRefillRate") !== capacity) {
        form.setValue("defaultRefillRate", capacity);
      }
    }
  }, [isWindowBased, form.watch("defaultCapacity"), form]);

  const onSubmit = (data: GlobalConfig) => {
    onUpdate(data);
  };

  return (
    <Card className="p-6 shadow-elevated backdrop-blur-sm bg-gradient-to-br from-card to-card/50">
      <div className="mb-6 flex items-center gap-2">
        <Settings className="h-5 w-5 text-primary" />
        <h3 className="text-lg font-semibold text-foreground">Global Default Configuration</h3>
      </div>

      <Form {...form}>
        <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-6">
          <div className="grid gap-6 md:grid-cols-2">
            <FormField
              control={form.control}
              name="algorithm"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Default Algorithm</FormLabel>
                  <Select onValueChange={field.onChange} defaultValue={field.value} value={field.value}>
                    <FormControl>
                      <SelectTrigger>
                        <SelectValue placeholder="Select an algorithm" />
                      </SelectTrigger>
                    </FormControl>
                    <SelectContent>
                      <SelectItem value="token-bucket">Token Bucket</SelectItem>
                      <SelectItem value="sliding-window">Sliding Window</SelectItem>
                      <SelectItem value="fixed-window">Fixed Window</SelectItem>
                      <SelectItem value="leaky-bucket">Leaky Bucket</SelectItem>
                    </SelectContent>
                  </Select>
                  <FormDescription>
                    Default rate limiting algorithm
                  </FormDescription>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name="defaultCapacity"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>{isWindowBased ? "Default Request Limit" : "Default Capacity"}</FormLabel>
                  <FormControl>
                    <Input
                      type="number"
                      {...field}
                      onChange={(e) => field.onChange(parseInt(e.target.value))}
                    />
                  </FormControl>
                  <FormDescription>
                    {isWindowBased ? "Maximum requests allowed in window" : "Maximum requests or tokens allowed for bursts"}
                  </FormDescription>
                  <FormMessage />
                </FormItem>
              )}
            />

            {!isWindowBased && (
              <FormField
                control={form.control}
                name="defaultRefillRate"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Default Refill Rate</FormLabel>
                    <FormControl>
                      <Input
                        type="number"
                        {...field}
                        onChange={(e) => field.onChange(parseInt(e.target.value))}
                      />
                    </FormControl>
                    <FormDescription>
                      Tokens per second or window size
                    </FormDescription>
                    <FormMessage />
                  </FormItem>
                )}
              />
            )}

            <FormField
              control={form.control}
              name="cleanupInterval"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Cleanup Interval (seconds)</FormLabel>
                  <FormControl>
                    <Input
                      type="number"
                      {...field}
                      onChange={(e) => field.onChange(parseInt(e.target.value))}
                    />
                  </FormControl>
                  <FormDescription>
                    How often to clean up expired entries
                  </FormDescription>
                  <FormMessage />
                </FormItem>
              )}
            />
          </div>

          <Button type="submit" className="w-full md:w-auto">
            Update Defaults
          </Button>
        </form>
      </Form>
    </Card>
  );
};
