import requests
import threading
import time
import random
import json

MANAGER_URL = "http://localhost:8080"
WORKER_URL = "http://localhost:8081"
USERS = [f"dyn_user_{i:02d}" for i in range(10)]
USER_PROFILES = [5, 15, 30, 50, 80, 110, 140, 170, 200, 250] # RPS for each user

class UserThread(threading.Thread):
    def __init__(self, user_id, rps):
        super().__init__()
        self.user_id = user_id
        self.rps = rps
        self.stop_event = threading.Event()
    def run(self):
        if self.rps <= 0: return
        delay = 1.0 / self.rps
        while not self.stop_event.is_set():
            start = time.time()
            try: requests.post(f"{WORKER_URL}/api/ratelimit/check", json={"key": self.user_id, "tokens": 1}, timeout=0.1)
            except: pass
            elapsed = time.time() - start
            time.sleep(max(0, delay - elapsed))

def set_mock(cpu, latency, error_rate=0.0):
    requests.post(f"{MANAGER_URL}/admin/adaptive-test/mock-health", 
                 json={"cpu": cpu, "latency": latency, "errorRate": error_rate})

def get_report(phase_name):
    print(f"\n>>> PHASE: {phase_name}")
    print(f"{'User ID':<12} | {'RPS':<5} | {'Cap':<6} | {'Refill':<8} | {'Decision':<12} | {'Z-Score'}")
    print("-" * 75)
    for i, uid in enumerate(USERS):
        try:
            resp = requests.get(f"{MANAGER_URL}/api/ratelimit/adaptive/{uid}/status").json()
            status = resp.get("adaptiveStatus", {})
            limits = resp.get("currentLimits", {})
            decision = status.get("reasoning", {}).get("decision", "N/A")
            profile = status.get("reasoning", {}).get("profile", "")
            zscore = profile.split("Z=")[1] if "Z=" in profile else "N/A"
            print(f"{uid:<12} | {USER_PROFILES[i]:<5} | {limits.get('capacity'):<6} | {limits.get('refillRate'):<8} | {decision:<12} | {zscore}")
        except:
            print(f"{uid:<12} | {USER_PROFILES[i]:<5} | ERROR FETCHING STATUS")

# 1. SETUP
print("Initializing 10 users with identical 100/20 baseline...")
for uid in USERS:
    requests.post(f"{MANAGER_URL}/api/ratelimit/config/keys/{uid}", 
                 json={"capacity": 100, "refillRate": 20, "adaptiveEnabled": True})
requests.delete(f"{MANAGER_URL}/admin/adaptive-test/mock-health")

threads = []
for i, uid in enumerate(USERS):
    t = UserThread(uid, USER_PROFILES[i])
    t.start()
    threads.append(t)

# PHASE 1: Healthy / Additive Increase
set_mock(0.20, 5.0) # Very healthy
print("\nWaiting 12 seconds for Additive Increase (Scaling beyond 100)...")
time.sleep(12)
get_report("HEALTHY (ADDITIVE INCREASE)")

# PHASE 2: CPU Stress / Punitive Decrease
set_mock(0.78, 10.0) # Above 70% target
print("\nWaiting 12 seconds for CPU Stress (Punitive Taxes)...")
time.sleep(12)
get_report("CPU STRESS (78% CPU)")

# PHASE 3: Latency Stress / Traffic Pressure
set_mock(0.30, 150.0) # High Latency (SLO is 100ms)
print("\nWaiting 12 seconds for Latency Stress (S_Traffic > 0.7)...")
time.sleep(12)
get_report("LATENCY STRESS (150ms)")

# PHASE 4: Recovery / Slow Start
set_mock(0.05, 1.0) # Back to healthy
print("\nWaiting 12 seconds for Recovery (Slow Start)...")
time.sleep(12)
get_report("RECOVERY (SLOW START)")

# CLEANUP
print("\nStopping test...")
for t in threads: t.stop_event.set()
for t in threads: t.join()
requests.delete(f"{MANAGER_URL}/admin/adaptive-test/mock-health")