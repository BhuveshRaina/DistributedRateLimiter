import requests
import threading
import time

# Config
MANAGER_URL = "http://localhost:8080"
WORKER_URL = "http://localhost:8081"
USERS = 50
SIM_TIME = 60

def setup():
    print("Setting up users...")
    for i in range(USERS):
        uid = f"citizen_{i}" if i > 0 else "villain"
        requests.post(f"{MANAGER_URL}/api/ratelimit/config/keys/{uid}", 
                     json={"capacity": 100, "refillRate": 20, "adaptiveEnabled": True})

def run_user(uid, rps):
    delay = 1.0 / rps
    while True:
        requests.post(f"{WORKER_URL}/api/ratelimit/check", json={"key": uid, "tokens": 1})
        time.sleep(delay)

setup()
print("Starting Load Test...")
# Villain makes 500 RPS
threading.Thread(target=run_user, args=("villain", 500), daemon=True).start()
# Citizens make 5 RPS
for i in range(1, USERS):
    threading.Thread(target=run_user, args=(f"citizen_{i}", 5), daemon=True).start()

print(f"Test running for {SIM_TIME}s. Force Stress via Mock API now!")
time.sleep(SIM_TIME)
print("Test complete.")
