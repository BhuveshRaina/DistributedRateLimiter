import requests
import threading
import time
import random
import sys
import json
from concurrent.futures import ThreadPoolExecutor

# Configuration
MANAGER_URL = "http://localhost:8080"
WORKER_URL = "http://localhost:8081"
NUM_USERS = 20 # Increased for more concurrency
PHASE_DURATION = 30  # seconds per phase
BASE_CAPACITY = 100
BASE_REFILL = 20

def setup_users():
    print(f"Setting up {NUM_USERS} users...")
    base_config = {
        "capacity": BASE_CAPACITY,
        "refillRate": BASE_REFILL,
        "algorithm": "TOKEN_BUCKET",
        "adaptiveEnabled": True
    }
    for i in range(NUM_USERS):
        user_id = f"sim_user_{i:02d}"
        requests.post(f"{MANAGER_URL}/api/ratelimit/config/keys/{user_id}", json=base_config)
        requests.delete(f"{MANAGER_URL}/api/ratelimit/adaptive/{user_id}/override")
    print("Setup complete.")

class DynamicUser:
    def __init__(self, user_id):
        self.user_id = user_id
        self.rps = 5  # Start low
        self.stop_event = threading.Event()
        
    def run(self):
        while not self.stop_event.is_set():
            # If RPS is very high, we don't sleep to maximize throughput
            start = time.time()
            try:
                requests.post(f"{WORKER_URL}/api/ratelimit/check", 
                               json={"key": self.user_id, "tokens": 1}, timeout=0.5)
            except: pass
            
            if self.rps < 500:
                delay = 1.0 / max(1, self.rps)
                elapsed = time.time() - start
                time.sleep(max(0, delay - elapsed))

def run_simulation():
    users = [DynamicUser(f"sim_user_{i:02d}") for i in range(NUM_USERS)]
    threads = []
    
    for u in users:
        t = threading.Thread(target=u.run)
        t.start()
        threads.append(t)

    try:
        # PHASE 1: Healthy (Observe INCREASE)
        print(f"\n>>> PHASE 1: HEALTHY (Low Load) - Goal: See Additive Increase")
        for u in users: u.rps = 10 # Light
        time.sleep(PHASE_DURATION)

        # PHASE 2: Stress (Observe DECREASE)
        print(f"\n>>> PHASE 2: STRESS (High Load) - Goal: See Multiplicative Decrease")
        # All users go to max speed (no sleep)
        for u in users: u.rps = 1000 
        time.sleep(PHASE_DURATION)

        # PHASE 3: Recovery (Observe SLOW START)
        print(f"\n>>> PHASE 3: RECOVERY (Zero Load) - Goal: See Slow Start / Recovery")
        for u in users: u.rps = 0 # Stop traffic
        time.sleep(PHASE_DURATION)

    finally:
        print("\nStopping simulation...")
        for u in users: u.stop_event.set()
        for t in threads: t.join()

    print("\nSimulation Finished. Check your backend console for the [TRANSITION] logs!")

if __name__ == "__main__":
    setup_users()
    run_simulation()