import { useEffect } from "react";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
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
import { KeyConfig } from "@/types/configuration";

import { Switch } from "@/components/ui/switch";
import { Brain } from "lucide-react";

const keyConfigSchema = z.object({
  keyName: z.string().min(1, "Key name is required").max(100),
  capacity: z.number().min(1, "Capacity must be at least 1").max(10000),
  refillRate: z.number().min(1, "Refill rate must be at least 1").max(1000),
  algorithm: z.enum(["token-bucket", "sliding-window", "fixed-window", "leaky-bucket"]),
  adaptiveEnabled: z.boolean().default(false),
  shadowMode: z.boolean().default(false),
});

type KeyConfigFormData = z.infer<typeof keyConfigSchema>;

interface KeyConfigModalProps {
  open: boolean;
  onClose: () => void;
  onSave: (config: Omit<KeyConfig, "id" | "createdAt" | "updatedAt">) => void;
  initialData?: KeyConfig;
  title: string;
}

export const KeyConfigModal = ({
  open,
  onClose,
  onSave,
  initialData,
  title,
}: KeyConfigModalProps) => {
  const form = useForm<KeyConfigFormData>({
    resolver: zodResolver(keyConfigSchema),
    defaultValues: {
      keyName: "",
      capacity: 10,
      refillRate: 5,
      algorithm: "token-bucket",
      adaptiveEnabled: false,
      shadowMode: false,
    },
  });

  const selectedAlgorithm = form.watch("algorithm");
  const isWindowBased = selectedAlgorithm === "fixed-window" || selectedAlgorithm === "sliding-window";

  useEffect(() => {
    if (initialData) {
      form.reset({
        keyName: initialData.keyName,
        capacity: initialData.capacity,
        refillRate: initialData.refillRate,
        algorithm: initialData.algorithm,
        adaptiveEnabled: initialData.adaptiveEnabled,
        shadowMode: initialData.shadowMode || false,
      });
    } else {
      form.reset({
        keyName: "",
        capacity: 10,
        refillRate: 5,
        algorithm: "token-bucket" as const,
        adaptiveEnabled: false,
        shadowMode: false,
      });
    }
  }, [initialData, form, open]);

  // Keep refillRate in sync with capacity for window-based algorithms
  useEffect(() => {
    if (isWindowBased) {
      const capacity = form.getValues("capacity");
      if (form.getValues("refillRate") !== capacity) {
        form.setValue("refillRate", capacity);
      }
    }
  }, [isWindowBased, form.watch("capacity"), form]);

  const onSubmit = (data: KeyConfigFormData) => {
    onSave(data as Omit<KeyConfig, "id" | "createdAt" | "updatedAt">);
    form.reset();
  };

  return (
    <Dialog open={open} onOpenChange={onClose}>
      <DialogContent className="sm:max-w-[500px]">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>
            Configure rate limiting settings for a specific API key
          </DialogDescription>
        </DialogHeader>

        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-4">
            <FormField
              control={form.control}
              name="keyName"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Key Name</FormLabel>
                  <FormControl>
                    <Input placeholder="rl_prod_user123" {...field} />
                  </FormControl>
                  <FormDescription>
                    Unique identifier for this API key
                  </FormDescription>
                  <FormMessage />
                </FormItem>
              )}
            />

            <div className="grid grid-cols-2 gap-4">
              <FormField
                control={form.control}
                name="algorithm"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Algorithm</FormLabel>
                    <Select onValueChange={field.onChange} value={field.value}>
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
                    <FormMessage />
                  </FormItem>
                )}
              />

              <FormField
                control={form.control}
                name="adaptiveEnabled"
                render={({ field }) => (
                  <FormItem className="flex flex-col justify-end">
                    <FormLabel className="flex items-center gap-2 mb-2">
                      <Brain className="h-4 w-4 text-primary" />
                      Adaptive
                    </FormLabel>
                    <FormControl>
                      <div className="flex items-center space-x-2 h-10 border rounded-md px-3 bg-muted/20">
                        <Switch
                          checked={field.value}
                          onCheckedChange={field.onChange}
                        />
                        <span className="text-xs text-muted-foreground">
                          {field.value ? "Enabled" : "Paused"}
                        </span>
                      </div>
                    </FormControl>
                  </FormItem>
                )}
              />

              <FormField
                control={form.control}
                name="shadowMode"
                render={({ field }) => (
                  <FormItem className="flex flex-col justify-end">
                    <FormLabel className="flex items-center gap-2 mb-2">
                      <Brain className="h-4 w-4 text-orange-500" />
                      Shadow Mode
                    </FormLabel>
                    <FormControl>
                      <div className="flex items-center space-x-2 h-10 border rounded-md px-3 bg-muted/20">
                        <Switch
                          checked={field.value}
                          onCheckedChange={field.onChange}
                        />
                        <span className="text-xs text-muted-foreground">
                          {field.value ? "Active" : "Off"}
                        </span>
                      </div>
                    </FormControl>
                  </FormItem>
                )}
              />
            </div>

            <FormField
              control={form.control}
              name="capacity"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>{isWindowBased ? "Request Limit" : "Capacity"}</FormLabel>
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
                name="refillRate"
                render={({ field }) => (
                  <FormItem>
                    <FormLabel>Refill Rate</FormLabel>
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

            <DialogFooter>
              <Button type="button" variant="outline" onClick={onClose}>
                Cancel
              </Button>
              <Button type="submit">Save Configuration</Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  );
};
