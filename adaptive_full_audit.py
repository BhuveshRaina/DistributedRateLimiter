import requests
import time
import threading
import sys

# --- CONFIGURATION ---
BASE_URL = "http://localhost:8080"
CHECK_URL = f"{BASE_URL}/api/ratelimit/check"
CONFIG_URL = f"{BASE_URL}/api/ratelimit/config/keys"
ADAPTIVE_URL = f"{BASE_URL}/api/ratelimit/adaptive"

# USERS & SCENARIOS
# 1. 'grower'   -> High traffic, healthy system = ADDITIVE_INCREASE
# 2. 'decaying' -> No traffic, high initial limit = IDLE_DECAY
# 3. 'steady'   -> Moderate traffic, should stay stable
USERS = {
    "grower": {"base_cap": 20, "base_refill": 5, "adaptive": True},
    "decaying": {"base_cap": 100, "base_refill": 20, "adaptive": True},
    "steady": {"base_cap": 30, "base_refill": 6, "adaptive": True},
}

def setup_environment():
    print(f"[*] Connecting to Manager at {BASE_URL}...")
    try:
        for key, cfg in USERS.items():
            payload = {
                "capacity": cfg["base_cap"],
                "refillRate": cfg["base_refill"],
                "algorithm": "TOKEN_BUCKET",
                "adaptiveEnabled": cfg["adaptive"]
            }
            res = requests.post(f"{CONFIG_URL}/{key}", json=payload, timeout=2)
            if res.status_code == 200:
                print(f"[+] Initialized {key}: Cap={cfg['base_cap']}, Adaptive=ON")
    except Exception as e:
        print(f"[!] Initialization failed: {e}")
        sys.exit(1)

def traffic_generator(key, rps, duration):
    """Sends continuous traffic to a specific key."""
    start = time.time()
    while time.time() - start < duration:
        try:
            requests.post(CHECK_URL, json={"key": key, "tokens": 1}, timeout=0.1)
        except:
            pass
        time.sleep(1.0 / rps)

def watch_loop(duration):
    """Prints the status of all keys every 5 seconds."""
    start = time.time()
    print("\n" + "="*80)
    print(f"{'KEY':<12} | {'MODE':<10} | {'CAPACITY':<10} | {'REASON':<25}")
    print("-" * 80)
    
    while time.time() - start < duration:
        try:
            # Use the bulk endpoint to get all statuses at once
            response = requests.get(f"{ADAPTIVE_URL}/all-statuses", timeout=2).json()
            # Sort by key name for consistent display
            response.sort(key=lambda x: x['key'])
            
            # Move cursor up to overwrite the previous block (for a 'dashboard' feel in CLI)
            # sys.stdout.write("\033[F" * (len(USERS) + 1)) 
            
            for item in response:
                key = item['key']
                if key in USERS:
                    adaptive_info = item['adaptiveStatus']
                    mode = adaptive_info['mode']
                    cap = item['currentLimits']['capacity']
                    reason = adaptive_info['reasoning'].get('decision', 'N/A')
                    print(f"{key:<12} | {mode:<10} | {cap:<10} | {reason:<25}")
            
            print("-" * 80)
        except Exception as e:
            print(f"[!] Watch error: {e}")
            
        time.sleep(5)

if __name__ == "__main__":
    setup_environment()
    
    # 1. Start Traffic for 'grower' (High load to force expansion)
    # 2. Start Traffic for 'steady' (Moderate load)
    # 3. 'decaying' gets NO traffic (to force idle decay)
    
    test_duration = 90 # Run for 1.5 minutes
    
    print(f"\n🚀 Starting Live Audit for {test_duration}s...")
    print("[*] Scenario 1: 'grower'   -> 20 RPS (High Load)")
    print("[*] Scenario 2: 'decaying' -> 0 RPS (Idle Decay)")
    print("[*] Scenario 3: 'steady'   -> 5 RPS (Stable)")
    print("\n[TIP] Open your browser to http://localhost:5173/adaptive now!")
    
    threads = [
        threading.Thread(target=traffic_generator, args=("grower", 20, test_duration)),
        threading.Thread(target=traffic_generator, args=("steady", 5, test_duration)),
        threading.Thread(target=watch_loop, args=(test_duration,))
    ]
    
    for t in threads:
        t.start()
        
    for t in threads:
        t.join()
        
    print("\n✅ Audit Complete.")
