import requests
import threading
import time
import json
import urllib3
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

MANAGER_URL = "http://localhost:8080"
WORKER_URL = "http://localhost:8081"

# The Cast of Characters
USERS = [
    {"id": "steady_norm", "base_cap": 100, "rps": 15},   # A completely average user
    {"id": "steady_high", "base_cap": 500, "rps": 60},   # A heavy but legitimate VIP user
    {"id": "sneaky_pulse","base_cap": 100, "rps": 5},    # A spammer who tries to hide
    {"id": "late_joiner", "base_cap": 200, "rps": 0}     # Joins halfway through the test
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
                time.sleep(0.1)
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
    print("\n" + "="*90)
    print(f">>> TRICKY PHASE: {phase_name}")
    print("="*90)
    print(f"{'User ID':<14} | {'RPS':<5} | {'Cap':<6} | {'Decision':<16} | {'Z-Score'}")
    print("-" * 85)
    for u in USERS:
        uid = u["id"]
        try:
            resp = requests.get(f"{MANAGER_URL}/api/ratelimit/adaptive/{uid}/status").json()
            status = resp.get("adaptiveStatus", {})
            limits = resp.get("currentLimits", {})
            decision = status.get("reasoning", {}).get("decision", "N/A")
            profile = status.get("reasoning", {}).get("profile", "")
            zscore = profile.split("Z=")[1] if "Z=" in profile else "0.00"
            
            # Clean formatting with characters
            if "DECREASE" in decision: decision_str = "R DECREASE"
            elif "PANIC" in decision: decision_str = "B PANIC"
            elif "HOLD" in decision or "EQUILIB" in decision or "Protected" in decision: decision_str = "W PROTECTED"
            elif "RECOVER" in decision: decision_str = "G FAST RECOVER"
            elif "INCREASE" in decision: decision_str = "G SLOW GROWTH"
            else: decision_str = decision
                
            print(f"{uid:<14} | {u['rps']:<5} | {limits.get('capacity', u['base_cap']):<6} | {decision_str:<16} | {zscore}")
        except Exception as e:
            print(f"{uid:<14} | Error fetching stats: {str(e)}")

# --- EXECUTION SCRIPT ---

print("Initializing Users for Tricky Scenarios...")
requests.delete(f"{MANAGER_URL}/admin/adaptive-test/mock-health")

for u in USERS:
    requests.post(f"{MANAGER_URL}/api/ratelimit/config/keys/{u['id']}", 
                 json={"capacity": u["base_cap"], "refillRate": u["base_cap"]//5, "adaptiveEnabled": True})
    # Warm up
    requests.post(f"{WORKER_URL}/api/ratelimit/check", json={"key": u['id'], "tokens": 1})

threads = {u["id"]: DynamicUserThread(u) for u in USERS}
for t in threads.values(): t.start()

try:
    # -------------------------------------------------------------------------
    # TRICK 1: The "False Alarm" (CPU 88%, Latency 10ms)
    # -------------------------------------------------------------------------
    set_mock(cpu=0.88, latency=10.0)
    print("\n[Trick 1] CPU is 88%, but API is fast. Testing 'Protected' state...")
    time.sleep(12)
    get_report("THE FALSE ALARM (CPU HIGH, LATENCY LOW)")

    # -------------------------------------------------------------------------
    # TRICK 2: The "Pulsing Spammer"
    # -------------------------------------------------------------------------
    print("\n[Trick 2] 'sneaky_pulse' blasts 300 RPS for 5 seconds, then tries to hide at 10 RPS...")
    threads["sneaky_pulse"].set_rps(300)
    USERS[2]["rps"] = 300
    set_mock(cpu=0.92, latency=60.0) # CPU spikes during attack
    time.sleep(5)
    
    threads["sneaky_pulse"].set_rps(10) # Spammer hides!
    USERS[2]["rps"] = 10
    time.sleep(7) # Engine evaluates while spammer is hiding
    get_report("THE PULSING SPAMMER (Z-SCORE MEMORY)")

    # -------------------------------------------------------------------------
    # TRICK 3: The "Boiling Frog" (Latency 85ms)
    # -------------------------------------------------------------------------
    set_mock(cpu=0.72, latency=85.0) 
    threads["late_joiner"].set_rps(20) # Late joiner arrives during the crisis
    USERS[3]["rps"] = 20
    print("\n[Trick 3] Latency hits 85ms (Just below panic). 'late_joiner' arrives...")
    time.sleep(12)
    get_report("THE BOILING FROG (SHARED SACRIFICE)")

    # -------------------------------------------------------------------------
    # TRICK 4: The "Two-Speed Recovery"
    # -------------------------------------------------------------------------
    set_mock(cpu=0.40, latency=15.0) 
    print("\n[Trick 4] Crisis ends. CPU 40%. Testing State C vs State D recovery...")
    time.sleep(12)
    get_report("THE TWO-SPEED RECOVERY")

finally:
    print("\nStopping test and cleaning up...")
    for t in threads.values(): t.stop_event.set()
    for t in threads.values(): t.join()
    requests.delete(f"{MANAGER_URL}/admin/adaptive-test/mock-health")