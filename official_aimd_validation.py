import requests
import threading
import time
import urllib3
import json
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

MANAGER_URL = "http://localhost:8080"
WORKER_URL = "http://localhost:8081"

# 12 Users: Heterogeneous Capacities and Traffic Profiles
USERS = [
    # The VIPs
    {"id": "vip_idle",      "base_cap": 1000, "base_refill": 200, "rps": 0},    # High limit, 0 traffic
    {"id": "vip_heavy",     "base_cap": 1000, "base_refill": 200, "rps": 120},  # High limit, legal heavy traffic
    
    # The Normal Crowd (Standard 100/20 limits)
    {"id": "standard_01",   "base_cap": 100,  "base_refill": 20,  "rps": 10},
    {"id": "standard_02",   "base_cap": 100,  "base_refill": 20,  "rps": 15},
    {"id": "standard_03",   "base_cap": 100,  "base_refill": 20,  "rps": 20},
    {"id": "standard_04",   "base_cap": 100,  "base_refill": 20,  "rps": 25},
    {"id": "standard_idle", "base_cap": 100,  "base_refill": 20,  "rps": 0},
    
    # The Anomalies (Spammers)
    {"id": "spam_micro",    "base_cap": 50,   "base_refill": 10,  "rps": 80},   # Small limit, hitting way above weight
    {"id": "spam_macro",    "base_cap": 500,  "base_refill": 100, "rps": 350},  # Massive volume anomaly
    
    # The Late Arrivals
    {"id": "late_norm",     "base_cap": 100,  "base_refill": 20,  "rps": 0},
    {"id": "late_spam",     "base_cap": 100,  "base_refill": 20,  "rps": 0}
]

class DynamicUserThread(threading.Thread):
    def __init__(self, user_info):
        super().__init__()
        self.user_id = user_info["id"]
        self.rps = user_info["rps"]
        self.stop_event = threading.Event()
        
    def set_rps(self, new_rps):
        self.rps = new_rps

    def run(self):
        while not self.stop_event.is_set():
            if self.rps <= 0:
                time.sleep(0.5)
                continue
                
            delay = 1.0 / self.rps
            start = time.time()
            try:
                requests.post(f"{WORKER_URL}/api/ratelimit/check", 
                              json={"key": self.user_id, "tokens": 1}, timeout=0.1)
            except: pass
            elapsed = time.time() - start
            time.sleep(max(0, delay - elapsed))

def set_mock(cpu, latency, error_rate=0.0):
    requests.post(f"{MANAGER_URL}/admin/adaptive-test/mock-health", 
                 json={"cpu": cpu, "latency": latency, "errorRate": error_rate})

def get_report(phase_name, expected_behavior):
    print("\n" + "="*95)
    print(f" 🧪 PHASE: {phase_name}")
    print(f" 🎯 EXPECTED: {expected_behavior}")
    print("="*95)
    print(f"{'User ID':<15} | {'RPS':<5} | {'Base Cap':<9} | {'Cur Cap':<8} | {'Z-Score':<7} | {'Decision / State'}")
    print("-" * 95)
    
    for u in USERS:
        uid = u["id"]
        try:
            resp = requests.get(f"{MANAGER_URL}/api/ratelimit/adaptive/{uid}/status").json()
            status = resp.get("adaptiveStatus", {})
            limits = resp.get("currentLimits", {})
            decision = status.get("reasoning", {}).get("decision", "N/A")
            profile = status.get("reasoning", {}).get("profile", "")
            zscore = profile.split("Z=")[1] if "Z=" in profile else "0.00"
            
            # Semantic Formatting
            if "PANIC" in decision: formatted_dec = "B PANIC (50% Cut)"
            elif "DECREASE" in decision: formatted_dec = "R MD (Taxed)"
            elif "HOLD" in decision: formatted_dec = "W PROTECTED"
            elif "EQUILIB" in decision: formatted_dec = "W EQUILIBRIUM"
            elif "RECOVER" in decision: formatted_dec = "G FAST RECOVER (x1.25)"
            elif "INCREASE" in decision: formatted_dec = "G SLOW GROWTH (Linear)"
            else: formatted_dec = decision
                
            cur_cap = limits.get('capacity', u['base_cap'])
            print(f"{uid:<15} | {u['rps']:<5} | {u['base_cap']:<9} | {cur_cap:<8} | {zscore:<7} | {formatted_dec}")
        except Exception as e:
            print(f"{uid:<15} | Error fetching metrics: {str(e)}")

# --- EXECUTION SCRIPT ---

print("Initializing Formal Validation Suite (12 Users)...")
requests.delete(f"{MANAGER_URL}/admin/adaptive-test/mock-health")

for u in USERS:
    requests.post(f"{MANAGER_URL}/api/ratelimit/config/keys/{u['id']}", 
                 json={"capacity": u["base_cap"], "refillRate": u["base_refill"], "adaptiveEnabled": True})
    # Warm up
    requests.post(f"{WORKER_URL}/api/ratelimit/check", json={"key": u['id'], "tokens": 1})

threads = {u["id"]: DynamicUserThread(u) for u in USERS}
for t in threads.values(): t.start()

try:
    # -------------------------------------------------------------------------
    # TEST 1: Baseline Stabilization (Additive Increase)
    # -------------------------------------------------------------------------
    set_mock(cpu=0.40, latency=20.0)
    print("\n[Running Test 1...] System Healthy. Evaluating Additive Growth constraints...")
    time.sleep(15) # Wait 3 cycles
    get_report("STATE D: ADDITIVE INCREASE", "Active users grow slowly. Idle users stay near base.")

    # -------------------------------------------------------------------------
    # TEST 2: Surgical Outlier Isolation (Hardware Noise)
    # -------------------------------------------------------------------------
    set_mock(cpu=0.82, latency=30.0) # CPU > 70%, but Latency < 100ms. Traffic is healthy!
    print("\n[Running Test 2...] CPU hits 82% but API is fast. Testing Anomaly Isolation...")
    time.sleep(15)
    get_report("STATE B: PUNATIVE DECREASE", "Spammers hit with MD. Normal/Idle users PROTECTED (Tax=0).")

    # -------------------------------------------------------------------------
    # TEST 3: System-Wide Degradation (Shared Sacrifice)
    # -------------------------------------------------------------------------
    set_mock(cpu=0.75, latency=85.0) # S_traffic > 0.7. DB is choking.
    threads["late_norm"].set_rps(20)
    USERS[9]["rps"] = 20
    print("\n[Running Test 3...] Latency climbs to 85ms. Testing Shared Sacrifice...")
    time.sleep(15)
    get_report("STATE B: SHARED SACRIFICE", "All active users receive 0.5 Tax. Idles under base are protected.")

    # -------------------------------------------------------------------------
    # TEST 4: The Catastrophic Failure (Circuit Breaker)
    # -------------------------------------------------------------------------
    set_mock(cpu=0.90, latency=50.0, error_rate=0.06) # Error rate 6% > SLO (5%)
    print("\n[Running Test 4...] Error rate hits 6% (SLO breached). Testing Panic Mode...")
    time.sleep(10) # 2 cycles is enough to crush
    get_report("STATE A: PANIC MODE", "Global 50% geometric load shed across ALL users.")

    # -------------------------------------------------------------------------
    # TEST 5: The Dual-Speed Recovery
    # -------------------------------------------------------------------------
    set_mock(cpu=0.30, latency=10.0, error_rate=0.0) # Crisis over
    print("\n[Running Test 5...] Crisis ends. Testing Exponential vs Linear recovery...")
    time.sleep(15)
    get_report("STATE C vs D: DUAL-SPEED RECOVERY", "Crushed users get Fast Recover. Healthy users get Slow Growth.")

    # -------------------------------------------------------------------------
    # TEST 6: Mathematical Equilibrium (The Deadzone)
    # -------------------------------------------------------------------------
    set_mock(cpu=0.70, latency=40.0) 
    print("\n[Running Test 6...] CPU perfectly hits 70% target. Testing Stability...")
    time.sleep(12)
    get_report("STATE E: EQUILIBRIUM", "Total system freeze. No limit changes. Math is stable.")

finally:
    print("\n✅ Formal Validation Suite Complete. Shutting down threads...")
    for t in threads.values(): t.stop_event.set()
    for t in threads.values(): t.join()
    requests.delete(f"{MANAGER_URL}/admin/adaptive-test/mock-health")