import { useState } from "react";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { toast } from "sonner";
import { rateLimiterApi, ScheduleResponse } from "@/services/rateLimiterApi";
import { ScheduleRequest, ScheduleType } from "@/types/scheduling";

interface ScheduleFormProps {
  schedule: ScheduleResponse | null;
  onClose: () => void;
  onSuccess: () => void;
}

/**
 * Helper to convert ISO string (UTC) to local datetime-local string (YYYY-MM-DDThh:mm)
 */
const toLocalISOString = (isoString: string | undefined): string => {
  if (!isoString) return '';
  const date = new Date(isoString);
  if (isNaN(date.getTime())) return '';
  
  // Adjust for local timezone offset
  const offset = date.getTimezoneOffset() * 60000;
  const localDate = new Date(date.getTime() - offset);
  return localDate.toISOString().slice(0, 16);
};

export const ScheduleForm = ({ schedule, onClose, onSuccess }: ScheduleFormProps) => {
  const [formData, setFormData] = useState<ScheduleRequest>({
    name: schedule?.name || '',
    keyPattern: schedule?.keyPattern || '',
    type: (schedule?.type as ScheduleType) || 'RECURRING',
    cronExpression: schedule?.cronExpression || '0 0 9-17 * * MON-FRI',
    timezone: schedule?.timezone || 'Asia/Kolkata',
    startTime: schedule?.startTime || '',
    endTime: schedule?.endTime || '',
    limits: {
      capacity: 100,
      refillRate: 10,
      algorithm: 'TOKEN_BUCKET',
    },
    fallbackLimits: {
      capacity: 10,
      refillRate: 2,
      algorithm: 'TOKEN_BUCKET',
    },
    priority: schedule?.priority || 0,
  });

  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);

    // Clean up irrelevant fields based on type
    const submitData = { ...formData };
    if (formData.type === 'RECURRING') {
      submitData.startTime = '';
      submitData.endTime = '';
    } else {
      submitData.cronExpression = '';
    }

    try {
      if (schedule) {
        await rateLimiterApi.updateSchedule(schedule.name, submitData);
        toast.success(`Schedule "${formData.name}" updated successfully`);
      } else {
        await rateLimiterApi.createSchedule(submitData);
        toast.success(`Schedule "${formData.name}" created successfully`);
      }
      onSuccess();
    } catch (error) {
      console.error('Failed to save schedule:', error);
      toast.error('Failed to save schedule');
    } finally {
      setLoading(false);
    }
  };

  const handleChange = (field: string, value: any) => {
    setFormData(prev => ({ ...prev, [field]: value }));
  };

  const handleLimitsChange = (field: keyof ScheduleRequest['limits'], value: any) => {
    setFormData(prev => ({
      ...prev,
      limits: { ...prev.limits, [field]: value },
    }));
  };

  return (
    <Dialog open onOpenChange={onClose}>
      <DialogContent className="max-w-2xl max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{schedule ? 'Edit Schedule' : 'Create Schedule'}</DialogTitle>
          <DialogDescription>
            Configure time-based rate limiting schedule
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={handleSubmit} className="space-y-4">
          {/* Basic Info */}
          <div className="grid gap-4 md:grid-cols-2">
            <div>
              <Label htmlFor="name">Schedule Name</Label>
              <Input
                id="name"
                value={formData.name}
                onChange={(e) => handleChange('name', e.target.value)}
                placeholder="e.g., business_hours"
                required
                disabled={!!schedule}
              />
            </div>
            <div>
              <Label htmlFor="keyPattern">Key Pattern</Label>
              <Input
                id="keyPattern"
                value={formData.keyPattern}
                onChange={(e) => handleChange('keyPattern', e.target.value)}
                placeholder="e.g., api:enterprise:*"
                required
              />
            </div>
          </div>

          {/* Schedule Type */}
          <div className="grid gap-4 md:grid-cols-2">
            <div>
              <Label htmlFor="type">Schedule Type</Label>
              <Select 
                value={formData.type} 
                onValueChange={(value) => handleChange('type', value)}
              >
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="RECURRING">Recurring (Cron)</SelectItem>
                  <SelectItem value="ONE_TIME">One-Time Event</SelectItem>
                  <SelectItem value="EVENT_DRIVEN">Event-Driven</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div>
              <Label htmlFor="timezone">Timezone</Label>
              <Input
                id="timezone"
                value={formData.timezone}
                onChange={(e) => handleChange('timezone', e.target.value)}
                placeholder="Asia/Kolkata"
                required
              />
            </div>
          </div>

          {/* Type-specific fields */}
          {formData.type === 'RECURRING' && (
            <div>
              <Label htmlFor="cronExpression">Cron Expression (6-field format)</Label>
              <Input
                id="cronExpression"
                value={formData.cronExpression}
                onChange={(e) => handleChange('cronExpression', e.target.value)}
                placeholder="0 0 9-17 * * MON-FRI"
                required
              />
              <p className="text-xs text-muted-foreground mt-1">
                Format: second minute hour day month day-of-week
              </p>
            </div>
          )}

          {(formData.type === 'ONE_TIME' || formData.type === 'EVENT_DRIVEN') && (
            <div className="grid gap-4 md:grid-cols-2">
              <div>
                <Label htmlFor="startTime">Start Time (Local)</Label>
                <Input
                  id="startTime"
                  type="datetime-local"
                  value={toLocalISOString(formData.startTime)}
                  onChange={(e) => handleChange('startTime', new Date(e.target.value).toISOString())}
                  required
                />
              </div>
              <div>
                <Label htmlFor="endTime">End Time (Local)</Label>
                <Input
                  id="endTime"
                  type="datetime-local"
                  value={toLocalISOString(formData.endTime)}
                  onChange={(e) => handleChange('endTime', new Date(e.target.value).toISOString())}
                  required
                />
              </div>
            </div>
          )}

          {/* Rate Limits */}
          <div>
            <h3 className="font-medium mb-2">Active Limits</h3>
            <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
              <div>
                <Label htmlFor="capacity">Capacity</Label>
                <Input
                  id="capacity"
                  type="number"
                  value={formData.limits.capacity}
                  onChange={(e) => handleLimitsChange('capacity', parseInt(e.target.value))}
                  required
                />
              </div>
              <div>
                <Label htmlFor="refillRate">Refill Rate</Label>
                <Input
                  id="refillRate"
                  type="number"
                  value={formData.limits.refillRate}
                  onChange={(e) => handleLimitsChange('refillRate', parseInt(e.target.value))}
                  required
                />
              </div>
              <div>
                <Label htmlFor="algorithm">Algorithm</Label>
                <Select 
                  value={formData.limits.algorithm || 'TOKEN_BUCKET'} 
                  onValueChange={(value) => handleLimitsChange('algorithm', value)}
                >
                  <SelectTrigger id="algorithm">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="TOKEN_BUCKET">Token Bucket</SelectItem>
                    <SelectItem value="FIXED_WINDOW">Fixed Window</SelectItem>
                    <SelectItem value="SLIDING_WINDOW">Sliding Window</SelectItem>
                    <SelectItem value="LEAKY_BUCKET">Leaky Bucket</SelectItem>
                  </SelectContent>
                </Select>
              </div>
              <div>
                <Label htmlFor="priority">Priority</Label>
                <Input
                  id="priority"
                  type="number"
                  value={formData.priority}
                  onChange={(e) => handleChange('priority', parseInt(e.target.value))}
                />
              </div>
            </div>
          </div>

          {/* Actions */}
          <div className="flex justify-end gap-2 pt-4">
            <Button type="button" variant="outline" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={loading}>
              {loading ? 'Saving...' : (schedule ? 'Update' : 'Create')}
            </Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
};