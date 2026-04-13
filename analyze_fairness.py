import requests
import threading
import time
import json

MANAGER_URL = "http://localhost:8080"
WORKER_URL = "http://localhost:8081"
USERS = [f"fair_user_{i:02d}" for i in range(10)]

class UserThread(threading.Thread):
    def __init__(self, user_id, rps):
        super().__init__()
        self.user_id = user_id
        self.rps = rps
        self.stop_event = threading.Event()
    def run(self):
        delay = 1.0 / self.rps if self.rps > 0 else 1.0
        while not self.stop_event.is_set():
            start = time.time()
            try: requests.post(f"{WORKER_URL}/api/ratelimit/check", json={"key": self.user_id, "tokens": 1}, timeout=0.2)
            except: pass
            elapsed = time.time() - start
            time.sleep(max(0, delay - elapsed))

def setup():
    print("Setting up 10 users with diverse profiles...")
    for user in USERS:
        requests.post(f"{MANAGER_URL}/api/ratelimit/config/keys/{user}", 
                     json={"capacity": 100, "refillRate": 20, "adaptiveEnabled": True})
    requests.delete(f"{MANAGER_URL}/admin/adaptive-test/mock-health")

def get_report():
    print(f"\n{'User ID':<15} | {'Type':<10} | {'Current Cap':<12} | {'Decision':<12} | {'Z-Score'}")
    print("-" * 75)
    for i, uid in enumerate(USERS):
        type = "VILLAIN" if i < 2 else ("MODERATE" if i < 5 else "CITIZEN")
        resp = requests.get(f"{MANAGER_URL}/api/ratelimit/adaptive/{uid}/status").json()
        status = resp.get("adaptiveStatus", {})
        cap = resp.get("currentLimits", {}).get("capacity")
        decision = status.get("reasoning", {}).get("decision")
        profile = status.get("reasoning", {}).get("profile", "")
        zscore = profile.split("Z=")[1] if "Z=" in profile else "N/A"
        print(f"{uid:<15} | {type:<10} | {cap:<12} | {decision:<12} | {zscore}")

# 1. Start Traffic
setup()
threads = []
for i, uid in enumerate(USERS):
    if i < 2: rps = 150 # Villains
    elif i < 5: rps = 40 # Moderate
    else: rps = 5 # Citizens
    t = UserThread(uid, rps)
    t.start()
    threads.append(t)

print("Letting traffic stabilize for 10 seconds...")
time.sleep(10)

# 2. Trigger Moderate Stress (75% CPU)
# This triggers DECREASE (Z-score logic) instead of PANIC
print("\n>>> APPLYING MODERATE STRESS (75% CPU)...")
requests.post(f"{MANAGER_URL}/admin/adaptive-test/mock-health", json={"cpu": 0.75, "latency": 10.0})

print("Waiting 15 seconds for AIMD to differentiate users...")
time.sleep(15)

# 3. Show Fairness Report
get_report()

# 4. Stop and Cleanup
print("\nStopping traffic...")
for t in threads: t.stop_event.set()
for t in threads: t.join()
requests.delete(f"{MANAGER_URL}/admin/adaptive-test/mock-health")
