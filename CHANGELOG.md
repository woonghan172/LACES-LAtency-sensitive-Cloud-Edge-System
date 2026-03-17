# LACES Project Changelog

---

## 2026-03-17

### `LacesEdgeOrchestrator.java` — Implement LACES routing decision

**File:** `EdgeCloudSim/src/edu/boun/edgecloudsim/applications/laces/LacesEdgeOrchestrator.java`

Filled in the previously empty `LACES` policy in `getDeviceToOffload()` with a score-driven edge vs. cloud routing decision.

#### Design formula

```
edgeScore  = 0.50 × edgeHeadroomScore
           + 0.35 × delaySensitivity
           + 0.15 × (1 - wanQualityScore)

cloudScore = 0.45 × wanQualityScore
           + 0.35 × (1 - edgeHeadroomScore)
           + 0.20 × taskLengthScore

result = (cloudScore > edgeScore) ? CLOUD : EDGE
```

#### Factor breakdown

| Factor | Source | Description |
|--------|--------|-------------|
| `edgeHeadroomScore` | `edgeUtilization` (avgUtil / 100) | Higher when edge is idle; favors keeping tasks at the edge |
| `delaySensitivity` | `taskLookUpTable[taskType][12]` | App-level latency sensitivity [0-1]; higher value pushes toward edge |
| `wanQualityScore` | `wanBW / WAN_GOOD_BW_MBPS` (capped at 1.0, threshold 5 Mbps) | Better WAN quality favors offloading to cloud |
| `taskLengthScore` | `task.getCloudletLength() / MAX_TASK_LENGTH_MI` (capped at 1.0, max 20000 MI) | Heavier tasks benefit more from cloud GPU |

---

### `applications.xml` — Set delay_sensitivity values

**File:** `EdgeCloudSim/scripts/laces/config/applications.xml`

Updated `delay_sensitivity` fields (previously all `0`) to values reflecting each application's latency requirements:

| Application | Old | New | Reason |
|-------------|-----|-----|--------|
| `AUGMENTED_REALITY` | 0 | **0.9** | Real-time AR is highly latency-sensitive; should run on edge when possible |
| `HEALTH_APP` | 0 | **0.5** | Health monitoring requires moderate responsiveness |
| `INFOTAINMENT_APP` | 0 | **0.2** | Entertainment/info apps tolerate higher latency |

---

## 2026-03-17 (follow-up)

### `LacesNetworkModel.java` — Fix empty downloadStarted / downloadFinished

**File:** `EdgeCloudSim/src/edu/boun/edgecloudsim/applications/laces/LacesNetworkModel.java`

**Problem:** `downloadStarted` and `downloadFinished` were empty stubs, so download traffic was never reflected in the `wanClients`/`wlanClients` counters. This caused WAN/WLAN delay estimates to be underestimated due to missing contention.

**Fix:** Implemented symmetrically with `uploadStarted`/`uploadFinished`, updating the same counters based on `sourceDeviceId`:
- `CLOUD_DATACENTER_ID` → `wanClients[ap]++/--`
- `GENERIC_EDGE_DEVICE_ID` → `wlanClients[ap]++/--`
- `GENERIC_EDGE_DEVICE_ID+1` (MAN) → `manClients++/--`

---
