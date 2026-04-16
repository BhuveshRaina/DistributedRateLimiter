import requests
import threading
import time
import sys
import subprocess

# --- CONFIGURATION ---
MANAGER_URL = "http://localhost:8080"
WORKER_URL = "http://localhost:8081" 
MANAGER_API = f"{MANAGER_URL}/api/ratelimit"
REDIS_GLOBAL_KEY = "ratelimiter:metrics:global"

def inject_stress_to_redis(p95_ms, requests_count):
    """Force Redis to report high latency to trigger MD."""
    total_time = int(p95_ms * requests_count)
    # Use HINCRBY to ensure deltas are positive and trigger the logic
    subprocess.run(["redis-cli", "HINCRBY", REDIS_GLOBAL_KEY, "totalAllowedRequests", str(requests_count)], capture_output=True)
    subprocess.run(["redis-cli", "HINCRBY", REDIS_GLOBAL_KEY, "totalProcessingTimeMs", str(total_time)], capture_output=True)
    subprocess.run(["redis-cli", "HINCRBY", REDIS_GLOBAL_KEY, "totalFailures", "0"], capture_output=True)

# --- TEST USERS ---
USERS = {
    "active_hero":     {"rps": 60, "base_cap": 20, "base_refill": 5,  "desc": "High Traffic -> Sharp MD"},
    "steady_citizen":  {"rps": 10, "base_cap": 20, "base_refill": 5,  "desc": "Normal Traffic -> Moderate MD"},
    "quiet_friend":    {"rps": 1,  "base_cap": 20, "base_refill": 5,  "desc": "Low Traffic -> Safe from MD"},
    "idle_bloat":      {"rps": 0,  "base_cap": 100, "base_refill": 20, "desc": "No Traffic -> Protected"},
    "recovering_soul": {"rps": 10, "base_cap": 50, "base_refill": 10, "desc": "Throttled Start -> Recovery"},
}

class TrafficGenerator(threading.Thread):
    def __init__(self, user_id, rps):
        super().__init__()
        self.user_id = user_id
        self.rps = rps
        self.stop_event = threading.Event()
        self.daemon = True

    def run(self):
        if self.rps <= 0: return
        delay = 1.0 / self.rps
        while not self.stop_event.is_set():
            start = time.time()
            try:
                # Target the Worker/LB port for real-world traffic simulation
                requests.post(f"{WORKER_URL}/api/ratelimit/check", 
                              json={"key": self.user_id, "tokens": 1}, timeout=0.2)
            except: pass
            elapsed = time.time() - start
            time.sleep(max(0, delay - elapsed))

def setup_environment():
    print("🏆 ADAPTIVE RATE LIMITER - MASTER VALIDATION SUITE")
    print("================================================")
    print("🔧 Initializing Real-Data environment...")
    
    # Cleanup global metrics and adaptive status first
    subprocess.run(["redis-cli", "DEL", REDIS_GLOBAL_KEY], capture_output=True)
    subprocess.run(["redis-cli", "DEL", "ratelimiter:adaptive:limits"], capture_output=True)
    subprocess.run(["redis-cli", "DEL", "ratelimiter:adaptive:tracked"], capture_output=True)

    for uid, cfg in USERS.items():
        # 1. Set Base Configuration in Manager
        payload = {
            "capacity": cfg['base_cap'],
            "refillRate": cfg['base_refill'],
            "algorithm": "TOKEN_BUCKET",
            "adaptiveEnabled": True
        }
        requests.post(f"{MANAGER_API}/config/keys/{uid}", json=payload)
        
        # 2. Setup starting states using temporary overrides
        if uid == "recovering_soul":
             # Force it low to see it recover
             requests.post(f"{MANAGER_API}/adaptive/{uid}/override", 
                           json={"capacity": 5, "refillRate": 1, "reason": "Pre-Test Throttle"})
             time.sleep(0.5)
             requests.delete(f"{MANAGER_API}/adaptive/{uid}/override")
        
        if uid == "idle_bloat":
             # Force it high to see it decay
             requests.post(f"{MANAGER_API}/adaptive/{uid}/override", 
                           json={"capacity": 200, "refillRate": 40, "reason": "Pre-Test Bloat"})
             time.sleep(0.5)
             requests.delete(f"{MANAGER_API}/adaptive/{uid}/override")

    # Force a global reload
    requests.post(f"{MANAGER_API}/config/reload")
    print("✅ Environment Ready.\n")

def run_phase(duration):
    print(f"🚀 Running Live Monitoring for {duration}s...")
    print("-" * 115)
    print(f"{'USER':<16} | {'LIMIT':<8} | {'BASE':<8} | {'DECISION':<18} | {'DESCRIPTION'}")
    print("-" * 115)

    threads = []
    for uid, cfg in USERS.items():
        t = TrafficGenerator(uid, cfg['rps'])
        threads.append(t)
        t.start()

    # Start stress injector thread
    def stress_injector():
        while True:
            # Inject 10ms latency over 1000 requests every 2 seconds
            # This will create a delta of 10ms in the Manager's evaluation
            inject_stress_to_redis(10.0, 1000)
            time.sleep(2)
    
    si = threading.Thread(target=stress_injector, daemon=True)
    si.start()

    start_time = time.time()
    try:
        while (time.time() - start_time) < duration:
            time.sleep(5) # Match the engine's 5s evaluation interval
            try:
                # Fetch metrics to see what the manager thinks
                met = requests.get(f"{MANAGER_URL}/metrics", timeout=1).json()
                print(f"[METRICS] Allowed: {met['totalAllowedRequests']}, Time: {met['totalProcessingTimeMs']}ms")

                # Fetch all statuses in one call
                res = requests.get(f"{MANAGER_API}/adaptive/all-statuses", timeout=2).json()
                statuses = {item['key']: item for item in res}
                
                for uid in USERS.keys():
                    s = statuses.get(uid)
                    if s:
                        cur_cap = s['currentLimits']['capacity']
                        base_cap = s['originalLimits']['capacity']
                        reason = s['adaptiveStatus']['reasoning'].get('decision', 'HOLD')
                        desc = USERS[uid]['desc']
                        print(f"{uid:<16} | {cur_cap:<8} | {base_cap:<8} | {reason:<18} | {desc}")
                    else:
                        print(f"{uid:<16} | {'EVICTED':<8} | {'-':<8} | {'N/A':<18} | {USERS[uid]['desc']}")
                print("." * 115)
            except Exception as e:
                print(f"[!] Monitoring Error: {e}")
    finally:
        for t in threads: t.stop_event.set()
        for t in threads: t.join()

if __name__ == "__main__":
    try:
        setup_environment()
        run_phase(60) # 60 seconds to observe MD cycles
        print("\n✅ Master Validation Complete.")
    except KeyboardInterrupt:
        print("\n[!] Test interrupted by user.")
        sys.exit(0)
