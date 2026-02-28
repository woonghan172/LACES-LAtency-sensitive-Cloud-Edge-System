import random
import numpy as np
from main import CoreCloud, EdgeCloud, UserDevice
import sys

# --- Read hot ratio ---
if len(sys.argv) > 1:
    hot_ratio = float(sys.argv[1])
else:
    hot_ratio = 0.7

# --- Setup ---
core = CoreCloud()
edge = EdgeCloud(core)
user = UserDevice(edge)

# --- WARMUP PHASE ---
print("Running warmup phase...")
user.capture_and_send("temperature_log_hot")
# clean warmup data cuz it doesn't count for latency metrics
user.latencies = []

# --- WORKLOAD ---
print(f"Running 1000 requests with hot ratio = {hot_ratio}")

random.seed(42)

requests = []

for _ in range(1000):
    if random.random() < hot_ratio:
        requests.append("temperature_log_hot")
    else:
        requests.append(f"temperature_log_{random.randint(1,100)}")

for req in requests:
    user.capture_and_send(req)

# --- Metrics ---
print("\n--- Summary Metrics (Warmup) ---")
print("Average latency:", np.mean(user.latencies))
print("P95 latency:", np.percentile(user.latencies, 95))
print("P99 latency:", np.percentile(user.latencies, 99))