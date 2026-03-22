import { useState, useEffect } from "react";
import { KeysOverview } from "@/components/apikeys/KeysOverview";
import { KeysTable } from "@/components/apikeys/KeysTable";
import { CreateKeyModal } from "@/components/apikeys/CreateKeyModal";
import { KeyDetailsPanel } from "@/components/apikeys/KeyDetailsPanel";
import { BulkOperationsDialog } from "@/components/apikeys/BulkOperationsDialog";
import { ApiHealthCheck } from "@/components/ApiHealthCheck";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { toast } from "sonner";
import { rateLimiterApi } from "@/services/rateLimiterApi";
import { ApiKey, ApiKeyCreateInput } from "@/types/apiKeys";
import { generateMockAccessLogs } from "@/utils/mockApiKeys";

const ApiKeys = () => {
  const [loading, setLoading] = useState(true);
  const [keys, setKeys] = useState<ApiKey[]>([]);
  const [selectedKeys, setSelectedKeys] = useState<string[]>([]);
  const [isCreateModalOpen, setIsCreateModalOpen] = useState(false);
  const [selectedKeyForDetails, setSelectedKeyForDetails] = useState<ApiKey | null>(null);
  const [isBulkDialogOpen, setIsBulkDialogOpen] = useState(false);
  const [keyToDelete, setKeyToDelete] = useState<string | null>(null);

  // Load active authentication keys from backend
  useEffect(() => {
    const loadKeys = async () => {
      try {
        const authKeys = await rateLimiterApi.getAuthApiKeys();
        
        // Convert backend ApiKeyInfo list to frontend ApiKey format
        const apiKeys: ApiKey[] = authKeys.map((info) => ({
          id: info.key,
          name: info.name,
          key: info.key,
          description: info.description,
          status: (info.status as any) || 'active',
          createdAt: info.createdAt,
          rateLimit: {
            capacity: 100,
            refillRate: 50,
            algorithm: "token-bucket",
          },
          usageStats: {
            totalRequests: 0,
            successfulRequests: 0,
            rateLimitedRequests: 0,
          },
          // Do not mark as mock by default - we want to see actual data
          _isMock: false 
        }));
        
        setKeys(apiKeys);
        setLoading(false);
      } catch (error) {
        console.error('Failed to load API keys:', error);
        toast.error('Failed to load API keys from backend');
        setLoading(false);
      }
    };

    loadKeys();
  }, []);

  const handleCreateKey = async (input: ApiKeyCreateInput) => {
    // Generate a secure-looking key if not provided
    const newKeyValue = `rl_${Array.from({ length: 32 }, () => Math.random().toString(36)[2]).join("")}`;
    const name = input.name || `Key ${newKeyValue.substring(0, 8)}...`;
    const description = input.description || "Persistent authentication API key";
    
    try {
      await rateLimiterApi.addAuthApiKey({
        key: newKeyValue,
        name: name,
        description: description
      });
      
      const newKey: ApiKey = {
        id: newKeyValue,
        name: name,
        key: newKeyValue,
        description: description,
        status: "active",
        createdAt: new Date().toISOString(),
        expiresAt: input.expiresAt?.toISOString(),
        rateLimit: input.useDefaultLimits
          ? { capacity: 100, refillRate: 50, algorithm: "token-bucket" }
          : {
              capacity: input.customLimits!.capacity,
              refillRate: input.customLimits!.refillRate,
              algorithm: input.customLimits!.algorithm,
            },
        usageStats: {
          totalRequests: 0,
          successfulRequests: 0,
          rateLimitedRequests: 0,
        },
        ipWhitelist: input.ipWhitelist,
        _isMock: false // NEW key in this session should have no logs
      };

      setKeys([newKey, ...keys]);
      toast.success("API key created successfully and saved to Redis");
    } catch (error) {
      toast.error("Failed to save API key to backend");
      console.error(error);
    }
    setIsCreateModalOpen(false);
  };

  const handleDeleteKey = async (id: string) => {
    try {
      await rateLimiterApi.deleteAuthApiKey(id);
      setKeys(keys.filter((k) => k.id !== id));
      setKeyToDelete(null);
      toast.success("API key deleted from Redis");
    } catch (error) {
      toast.error("Failed to delete API key from backend");
      console.error(error);
    }
  };

  const handleBulkActivate = () => {
    setKeys(
      keys.map((k) => (selectedKeys.includes(k.id) ? { ...k, status: "active" as const } : k))
    );
    setSelectedKeys([]);
    toast.success(`Activated ${selectedKeys.length} API keys`);
  };

  const handleBulkDeactivate = () => {
    setKeys(
      keys.map((k) => (selectedKeys.includes(k.id) ? { ...k, status: "inactive" as const } : k))
    );
    setSelectedKeys([]);
    toast.success(`Deactivated ${selectedKeys.length} API keys`);
  };

  const handleBulkDelete = () => {
    setKeys(keys.filter((k) => !selectedKeys.includes(k.id)));
    setSelectedKeys([]);
    toast.success(`Deleted ${selectedKeys.length} API keys`);
  };

  const handleBulkExport = () => {
    const selectedKeysData = keys.filter((k) => selectedKeys.includes(k.id));
    const blob = new Blob([JSON.stringify(selectedKeysData, null, 2)], {
      type: "application/json",
    });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `api-keys-${Date.now()}.json`;
    a.click();
    URL.revokeObjectURL(url);
    toast.success("API keys exported successfully");
  };

  const handleRegenerateKey = () => {
    if (selectedKeyForDetails) {
      const newKey = `rl_${Array.from({ length: 32 }, () =>
        Math.random().toString(36)[2]
      ).join("")}`;
      setKeys(
        keys.map((k) => (k.id === selectedKeyForDetails.id ? { ...k, key: newKey } : k))
      );
      toast.success("API key regenerated successfully");
      setSelectedKeyForDetails(null);
    }
  };

  return (
    <div className="space-y-6 animate-fade-in">
      <div>
        <h2 className="text-3xl font-bold tracking-tight text-foreground">API Keys</h2>
        <p className="text-muted-foreground">
          Manage API keys for authentication and access control
        </p>
      </div>

      <ApiHealthCheck />

      <KeysOverview
        keys={keys}
        onCreateNew={() => setIsCreateModalOpen(true)}
        onBulkOperations={() => setIsBulkDialogOpen(true)}
      />

      <KeysTable
        keys={keys}
        selectedKeys={selectedKeys}
        onSelectionChange={setSelectedKeys}
        onView={setSelectedKeyForDetails}
        onEdit={(key) => toast.info("Edit functionality coming soon")}
        onDelete={(id) => setKeyToDelete(id)}
      />

      <CreateKeyModal
        open={isCreateModalOpen}
        onClose={() => setIsCreateModalOpen(false)}
        onCreate={handleCreateKey}
      />

      <KeyDetailsPanel
        keyData={selectedKeyForDetails}
        accessLogs={selectedKeyForDetails?._isMock ? generateMockAccessLogs() : []}
        open={!!selectedKeyForDetails}
        onClose={() => setSelectedKeyForDetails(null)}
        onEdit={() => toast.info("Edit functionality coming soon")}
        onDelete={() => {
          if (selectedKeyForDetails) {
            handleDeleteKey(selectedKeyForDetails.id);
            setSelectedKeyForDetails(null);
          }
        }}
        onRegenerate={handleRegenerateKey}
      />

      <BulkOperationsDialog
        open={isBulkDialogOpen}
        onClose={() => setIsBulkDialogOpen(false)}
        selectedCount={selectedKeys.length}
        onActivate={handleBulkActivate}
        onDeactivate={handleBulkDeactivate}
        onDelete={handleBulkDelete}
        onExport={handleBulkExport}
      />

      <AlertDialog open={!!keyToDelete} onOpenChange={() => setKeyToDelete(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete API Key</AlertDialogTitle>
            <AlertDialogDescription>
              Are you sure you want to delete this API key? This action cannot be undone and any
              applications using this key will lose access immediately.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => keyToDelete && handleDeleteKey(keyToDelete)}
              className="bg-destructive"
            >
              Delete
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
};

export default ApiKeys;
