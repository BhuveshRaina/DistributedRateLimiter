import requests
import threading
import time
import json
import urllib3
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

MANAGER_URL = "http://localhost:8080"
WORKER_URL = "http://localhost:8081"

# Mixed configuration: Some users have huge limits, some have small.
USERS_CONFIG = [
    {"id": "idle_vip",   "base_cap": 1000, "rps": 0},    # Sends nothing
    {"id": "normal_01",  "base_cap": 200,  "rps": 15},   # Good citizen
    {"id": "normal_02",  "base_cap": 200,  "rps": 25},   # Good citizen
    {"id": "spammer_01", "base_cap": 100,  "rps": 150},  # Abusive user
    {"id": "spammer_02", "base_cap": 100,  "rps": 200}   # Extreme abuser
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

def get_report(phase_name):
    print(f"\n" + "="*95)
    print(f">>> PHASE: {phase_name}")
    print("="*95)
    print(f"{'User ID':<12} | {'Set RPS':<7} | {'Cap':<6} | {'Refill':<6} | {'Decision':<18} | {'Z-Score'}")
    print("-" * 85)
    for u in USERS_CONFIG:
        uid = u["id"]
        try:
            resp = requests.get(f"{MANAGER_URL}/api/ratelimit/adaptive/{uid}/status").json()
            status = resp.get("adaptiveStatus", {})
            limits = resp.get("currentLimits", {})
            decision = status.get("reasoning", {}).get("decision", "N/A")
            profile = status.get("reasoning", {}).get("profile", "")
            zscore = profile.split("Z=")[1] if "Z=" in profile else "0.00"
            
            # Highlight decisions
            if "DECREASE" in decision or "PANIC" in decision: decision_str = f"R {decision}"
            elif "HOLD" in decision or "EQUILIB" in decision: decision_str = f"W {decision}"
            elif "INCREASE" in decision or "RECOVER" in decision: decision_str = f"G {decision}"
            else: decision_str = decision
                
            print(f"{uid:<12} | {u['rps']:<7} | {limits.get('capacity', u['base_cap']):<6} | {limits.get('refillRate', u['base_cap']):<6} | {decision_str:<18} | {zscore}")
        except Exception as e:
            print(f"{uid:<12} | Error fetching stats: {str(e)}")

# --- EXECUTION SCRIPT ---

print("Initializing Heterogeneous User Base...")
requests.delete(f"{MANAGER_URL}/admin/adaptive-test/mock-health")

for u in USERS_CONFIG:
    requests.post(f"{MANAGER_URL}/api/ratelimit/config/keys/{u['id']}", 
                 json={"capacity": u["base_cap"], "refillRate": u["base_cap"]//5, "adaptiveEnabled": True})
    # Warm up with one request to ensure they are tracked
    requests.post(f"{WORKER_URL}/api/ratelimit/check", json={"key": u['id'], "tokens": 1})

# Start traffic
threads = []
for u in USERS_CONFIG:
    t = DynamicUserThread(u)
    t.start()
    threads.append(t)

try:
    # -------------------------------------------------------------------------
    # SCENARIO 1: The "Background Noise" Test (High CPU, Good API)
    # CPU is 95%. But Latency is fast (20ms) and Errors are 0.
    # EXPECTED: Spammers are punished heavily. Normals are PROTECTED (Hold).
    # -------------------------------------------------------------------------
    set_mock(cpu=0.95, latency=20.0, error_rate=0.0)
    print("\n[Scenario 1] CPU spiking to 95%, but API is fast. Testing Anomaly Isolation...")
    time.sleep(12)
    get_report("BACKGROUND OS NOISE (CPU 95%)")

    # -------------------------------------------------------------------------
    # SCENARIO 2: The "Silent Assassin" (Database Crash)
    # CPU is freezing cold (10%). But 5xx Errors hit 6% (SLO is 5%).
    # EXPECTED: GLOBAL PANIC. 50% cut across the board.
    # -------------------------------------------------------------------------
    set_mock(cpu=0.10, latency=50.0, error_rate=0.06) 
    print("\n[Scenario 2] CPU is cold (10%), but Database threw 6% Errors. Testing Fail-Fast...")
    time.sleep(12)
    get_report("SILENT ASSASSIN (ERROR RATE 6%)")

    # -------------------------------------------------------------------------
    # SCENARIO 3: The "Flash Crowd" (Zero Variance)
    # Everyone suddenly sends the exact same traffic (60 RPS).
    # EXPECTED: Z-Scores drop to ~0. The system must use "Shared Sacrifice" tax.
    # -------------------------------------------------------------------------
    print("\n[Scenario 3] Flash Crowd arriving! All active users syncing to 60 RPS...")
    for t in threads:
        if t.user_id != "idle_vip":  # Keep the VIP idle
            t.set_rps(60)
            for u in USERS_CONFIG:
                if u["id"] == t.user_id: u["rps"] = 60
                
    # Moderate stress to trigger the Shared Sacrifice
    set_mock(cpu=0.80, latency=80.0, error_rate=0.0) 
    time.sleep(12)
    get_report("FLASH CROWD (EQUAL TRAFFIC + MODERATE STRESS)")

    # -------------------------------------------------------------------------
    # SCENARIO 4: Perfect Equilibrium
    # System stabilizes exactly at the 70% target.
    # EXPECTED: Complete mathematical freeze (Deadzone). No Redis writes.
    # -------------------------------------------------------------------------
    set_mock(cpu=0.70, latency=40.0, error_rate=0.0) 
    print("\n[Scenario 4] System hits exactly 70% CPU. Testing the Deadzone...")
    time.sleep(12)
    get_report("PERFECT EQUILIBRIUM (TARGET 70%)")

finally:
    print("\nStopping test and cleaning up...")
    for t in threads: t.stop_event.set()
    for t in threads: t.join()
    requests.delete(f"{MANAGER_URL}/admin/adaptive-test/mock-health")