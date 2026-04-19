# HEALTH_APP vs INFOTAINMENT_APP Configuration Comparison

## Configuration Differences Table

| Parameter | HEALTH_APP | INFOTAINMENT_APP | Difference | Meaning |
|-----------|------------|------------------|------------|---------|
| **usage_percentage** | 20 | 50 | +30 | Infotainment has higher usage share |
| **prob_cloud_selection** | 20 | 10 | -10 | Health is more likely to select cloud |
| **poisson_interarrival** | 3 | 7 | +4 | Infotainment has a lower arrival rate (longer intervals) |
| **delay_sensitivity** | 0.7 | 0.3 | -0.4 | Health is much more delay-sensitive (needs faster response) |
| **active_period** | 45 | 30 | -15 | Health stays active longer |
| **idle_period** | 90 | 45 | -45 | Health has longer idle windows |
| **data_upload** | 20 | 25 | +5 | Small difference |
| **data_download** | 1250 | 1000 | -250 | Health downloads slightly more data |
| **task_length** | 3000 | 15000 | +12000 | **Infotainment has 5x heavier computation** |
| **required_core** | 1 | 1 | 0 | Same |
| **vm_utilization_on_edge** | 2 | 10 | +8 | Infotainment consumes more edge VM resources |
| **vm_utilization_on_cloud** | 0.2 | 1 | +0.8 | Infotainment consumes more cloud VM resources |
| **vm_utilization_on_mobile** | 8 | 40 | +32 | **Infotainment consumes 5x more mobile resources** |

---

## Key Difference Analysis

### Delay Sensitivity (`delay_sensitivity`)
- **HEALTH_APP: 0.7** <- high sensitivity
- **INFOTAINMENT_APP: 0.3** <- low sensitivity (can tolerate delay)
- **Implication**: Health tends to prefer local/edge execution, while Infotainment can better tolerate cloud delay.

### Computation Demand (`task_length`)
- **HEALTH_APP: 3000 MI**
- **INFOTAINMENT_APP: 15000 MI** <- **5x difference**
- **Implication**: Infotainment is compute-intensive and cloud-friendly; Health is lightweight and edge-friendly.

### Resource Consumption (`vm_utilization_*`)

#### Edge Execution
- Health: 2 (low)
- Infotainment: 10 (5x higher) <- heavy tasks put more pressure on edge VMs

#### Mobile Execution
- Health: 8 (moderate)
- Infotainment: 40 (5x higher) <- much higher battery/resource impact on mobile devices

#### Cloud Execution
- Health: 0.2 (very low)
- Infotainment: 1 (normal)

### Traffic Pattern
- **HEALTH_APP**:
  - Download 1250 (high) / Upload 20 (low) <- receives relatively large result data
  - Lower arrival intensity (`poisson_interarrival=3`)

- **INFOTAINMENT_APP**:
  - Download 1000 / Upload 25 <- more balanced traffic
  - Lower request frequency (`poisson_interarrival=7` means longer intervals)

### Usage Behavior
- **HEALTH_APP**: usage 20% + high sensitivity (0.7)
  - Active 45s / Idle 90s <- monitoring-like burst + long idle pattern

- **INFOTAINMENT_APP**: usage 50% (primary workload)
  - Active 30s / Idle 45s <- frequent but less urgent usage pattern

---

## Why Is the Best Weight the Same (W1=0.15, W2=0.80, W3=0.05)?

### From the HEALTH_APP perspective
```
Delay-sensitive (0.7) -> prefers edge/local execution
Lightweight tasks (3000 MI) -> edge can handle them efficiently
Conclusion: high W2 (computation) + moderate W1 (latency) supports fast and stable edge decisions
```

### From the INFOTAINMENT_APP perspective
```
Compute-intensive (15000 MI) -> needs cloud/edge assistance
Lower sensitivity (0.3) -> can tolerate waiting
High mobile resource usage (40) -> poor fit for mobile execution
Conclusion: high W2 (computation) + low W3 (data transfer) supports efficiency-focused decisions
```

### Shared strategy
**W2=0.80 (computation cost) as the dominant weight** works for both apps because:
1. **Health**: lightweight but still needs cost-aware placement.
2. **Infotainment**: heavy tasks require strong compute-aware balancing.

---

## Conclusion

| Aspect | HEALTH_APP | INFOTAINMENT_APP |
|--------|------------|------------------|
| **Workload profile** | Monitoring-like (light + urgent) | Entertainment-like (heavy + delay-tolerant) |
| **Preferred target** | Edge-first | Cloud-first (or edge-assisted) |
| **Weight priority** | Latency > Computation > Data | Computation > Data > Latency |
| **Observed best weight** | W1=0.15 W2=0.80 W3=0.05 | W1=0.15 W2=0.80 W3=0.05 |
| **Reason** | Balanced strategy with compute emphasis | Balanced strategy with compute emphasis |

The same recommended weight for both apps indicates:
- **Computation cost (W2)** is the dominant driver (80%).
- **Latency and transfer cost** are secondary (15% and 5%).
- This balance generalizes well across different workload types.
