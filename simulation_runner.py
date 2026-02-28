import random
import numpy as np
import sys
from main import CoreCloud, EdgeCloud, UserDevice

# --- Read hot ratio ---
if len(sys.argv) > 1:
    hot_ratio = float(sys.argv[1])
else:
    hot_ratio = 0.7

print(f"Simulating 1000 requests with hot ratio = {hot_ratio}")

random.seed(42)

core = CoreCloud()
edge = EdgeCloud(core)
user = UserDevice(edge)

# --- WORKLOAD ---
requests = []

for _ in range(1000):
    # I assume only one temperature_log_hot
    if random.random() < hot_ratio:
        requests.append("temperature_log_hot")
    else:
        requests.append(f"temperature_log_{random.randint(1,100)}")

for req in requests:
    user.capture_and_send(req)

print("\n--- Summary Metrics ---")
print("Average latency:", np.mean(user.latencies))
print("P95 latency:", np.percentile(user.latencies, 95))
print("P99 latency:", np.percentile(user.latencies, 99))