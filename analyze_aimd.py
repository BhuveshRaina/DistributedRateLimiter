import requests
import time
import json

MANAGER_URL = "http://localhost:8080"
WORKER_URL = "http://localhost:8081"
USERS = [f"diag_user_{i:02d}" for i in range(10)]

def log_test(step):
    print(f"\n--- STEP: {step} ---")

def set_mock(cpu, latency):
    requests.post(f"{MANAGER_URL}/admin/adaptive-test/mock-health", 
                 json={"cpu": cpu, "latency": latency})
    print(f"Applied Mock: CPU={cpu*100}%, Latency={latency}ms")

def check_results():
    results = {}
    for user in USERS:
        resp = requests.get(f"{MANAGER_URL}/api/ratelimit/adaptive/{user}/status").json()
        adaptive_status = resp.get("adaptiveStatus", {})
        results[user] = {
            "cap": resp.get("currentLimits", {}).get("capacity"),
            "decision": adaptive_status.get("reasoning", {}).get("decision"),
            "telemetry": adaptive_status.get("reasoning", {}).get("telemetry")
        }
    return results

# 1. Setup
log_test("Initializing 10 users at 100/20")
for user in USERS:
    requests.post(f"{MANAGER_URL}/api/ratelimit/config/keys/{user}", 
                 json={"capacity": 100, "refillRate": 20, "adaptiveEnabled": True})
    # Send one request to make them 'active'
    requests.post(f"{WORKER_URL}/api/ratelimit/check", json={"key": user, "tokens": 1})

# 2. Stress Phase
log_test("Simulating CRITICAL STRESS (90% CPU + 200ms Latency)")
set_mock(0.90, 200.0)
print("Waiting 10 seconds for AIMD cycle...")
time.sleep(10)

stress_results = check_results()
print(f"{'User ID':<15} | {'Stress Cap':<15} | {'Stress Refill':<15} | {'Decision'}")
print("-" * 60)
for user, data in stress_results.items():
    # Fetch full status to get refill rate
    resp = requests.get(f"{MANAGER_URL}/api/ratelimit/adaptive/{user}/status").json()
    refill = resp.get("currentLimits", {}).get("refillRate")
    print(f"{user:<15} | {data['cap']:<15} | {refill:<15} | {data['decision']}")

# 3. Recovery Phase
log_test("Simulating HEALTHY system (5% CPU)")
set_mock(0.05, 1.0)
print("Waiting 7 seconds for Recovery cycle...")
time.sleep(7)

recovery_results = check_results()
print(f"\n{'User ID':<15} | {'Recover Cap':<15} | {'Recover Refill':<15} | {'Decision'}")
print("-" * 60)
for user, data in recovery_results.items():
    resp = requests.get(f"{MANAGER_URL}/api/ratelimit/adaptive/{user}/status").json()
    refill = resp.get("currentLimits", {}).get("refillRate")
    print(f"{user:<15} | {data['cap']:<15} | {refill:<15} | {data['decision']}")

# 4. Final Verdict
print("\n" + "="*30)
print("VERDICT ANALYSIS")
print("="*30)

success = True
sample_user = USERS[0]
if stress_results[sample_user]['decision'] in ["DECREASE", "PANIC"] and stress_results[sample_user]['cap'] < 100:
    print("[PASS] AIMD correctly detected stress and throttled limits.")
else:
    print("[FAIL] AIMD failed to throttle during stress.")
    success = False

if recovery_results[sample_user]['decision'] == "RECOVERING" and recovery_results[sample_user]['cap'] > stress_results[sample_user]['cap']:
    print("[PASS] Slow Start logic correctly triggered recovery.")
else:
    print("[FAIL] Recovery phase not detected.")
    success = False

if success:
    print("\nOVERALL VERDICT: YOUR ADAPTIVE SYSTEM IS WORKING PERFECTLY.")
else:
    print("\nOVERALL VERDICT: SYSTEM FAILED CALIBRATION.")

# Cleanup
requests.delete(f"{MANAGER_URL}/admin/adaptive-test/mock-health")
