# LACES Project Changelog

---

## 2026-03-21

### LACES policy — Upgrade to 3-target weighted cost selection (Device / Edge / Cloud)

**Files:**
- `EdgeCloudSim/src/edu/boun/edgecloudsim/applications/laces/LacesEdgeOrchestrator.java`
- `EdgeCloudSim/src/edu/boun/edgecloudsim/applications/laces/LacesMobileDeviceManager.java`
- `EdgeCloudSim/src/edu/boun/edgecloudsim/core/SimSettings.java`
- `EdgeCloudSim/scripts/laces/config/default_config.properties`

Implemented a weighted-cost orchestration flow for `LACES` that evaluates **three candidates** (mobile device, edge, cloud) and selects the target with the **lowest** total score.

#### Decision rule

```
score = w_latency * T_net + w_computation * T_processing + w_data * T_transmission
target = argmin(score_device, score_edge, score_cloud)
```

#### Latency mapping (per latest policy definition)

- `T_net(device) = 0`
- `T_net(edge) = MAN delay probe`
- `T_net(cloud) = WAN delay probe`

#### What changed in code

- Added 3-way scoring in `LacesEdgeOrchestrator`:
    - Candidate VM feasibility check per target (mobile / edge / cloud)
    - Per-target `T_processing` derived from predicted required CPU vs current VM headroom
    - Per-target `T_transmission` from payload size and measured link quality
    - Final decision by minimum score
- Extended `getVmToOffload(...)` in `LacesEdgeOrchestrator` to support `MOBILE_DATACENTER_ID` selection path.
- Added local-execution event path in `LacesMobileDeviceManager`:
    - New `REQUEST_RECEIVED_BY_MOBILE_DEVICE` flow
    - Local execution now bypasses upload/download network legs cleanly
    - Correctly logs task lifecycle for mobile-local completion
- Added configurable LACES weights in `SimSettings` and properties parsing:
    - `laces_weight_latency`
    - `laces_weight_computation`
    - `laces_weight_data`
- Added corresponding defaults in `scripts/laces/config/default_config.properties`:
    - `0.5 / 0.3 / 0.2`

---

## 2026-03-21 (follow-up)

### `IdleActiveLoadGenerator.java` — Fix app selection gap when usage sum != 100

**File:** `EdgeCloudSim/src/edu/boun/edgecloudsim/task_generator/IdleActiveLoadGenerator.java`

Adjusted task-type sampling to use the **actual total** of configured `usage_percentage` values, instead of sampling against a fixed `[0,100]` range.

#### Why this was needed

When `usage_percentage` values did not sum to 100 (for example 30+20+30=80), the remaining probability window caused unassigned devices and repeated log messages:

`Critical Error: No valid task type assigned to device ...`

#### Result

- Devices now always sample from valid configured app weights.
- No more false task-type assignment gaps when totals are below 100.
- Existing `applications.xml` values can be kept as-is.

---

### Single-app experiment configs — AR / Health / Info only

**Files:**
- `EdgeCloudSim/scripts/laces/config/applications_only_ar.xml`
- `EdgeCloudSim/scripts/laces/config/applications_only_health.xml`
- `EdgeCloudSim/scripts/laces/config/applications_only_info.xml`

Added three dedicated application config files for one-app-at-a-time experiments.

Each file sets exactly one app to `usage_percentage=100` and the other two to `0`, enabling isolated measurement per app before weight grid-search tuning.

---

## 2026-03-21 (experiment workflow note)

### How to run weight-tuning experiments + suggested folder naming

**Related file:**
- `EdgeCloudSim/scripts/laces/python/tune_weight_grid.py`

Added a practical workflow note for running single-app weight tuning experiments and organizing outputs for later analysis.

#### Recommended execution flow

1. **Compile once**

```bash
cd EdgeCloudSim/scripts/laces
bash ./compile.sh
```

2. **Run one app at a time** using dedicated app config files:

- `applications_only_ar.xml`
- `applications_only_health.xml`
- `applications_only_info.xml`

Example (AR):

```bash
cd EdgeCloudSim/scripts/laces
bash ./runner.sh output/exp_ar default_config edge_devices.xml applications_only_ar.xml 1
```

3. **For weight grid-search experiments**

- Keep one app fixed (AR-only / Health-only / Info-only)
- For each weight triplet, run **8 iterations**
- Collect per-run metrics: `service_time`, `failed_task`

4. **Rank best weights** with Python tool:

```bash
cd EdgeCloudSim/scripts/laces/python
python3 tune_weight_grid.py \
    --mode sim \
    --input-csv <your_aggregated_metrics.csv> \
    --iterations 8 \
    --output-csv <ranked_weights.csv>
```

Ranking rule in `sim` mode:

- Primary: `failed_task_mean` (ascending)
- Secondary: `service_time_mean` (ascending)

#### Suggested folder structure

```text
scripts/laces/output/weight_tuning/
    ar/
        w1_0.50_w2_0.30_w3_0.20/
            ite1.log
            ite2.log
            ...
            ite8.log
            summary.csv
        w1_0.55_w2_0.25_w3_0.20/
    health/
    info/
```

#### Suggested naming rules

- Weight folder: `w1_<w1>_w2_<w2>_w3_<w3>`
    - Example: `w1_0.50_w2_0.30_w3_0.20`
- App folder: `ar`, `health`, `info`
- Aggregated CSV per app:
    - `ar_metrics_all_weights.csv`
    - `health_metrics_all_weights.csv`
    - `info_metrics_all_weights.csv`
- Final ranking outputs:
    - `ar_ranked_weights.csv`
    - `health_ranked_weights.csv`
    - `info_ranked_weights.csv`

This structure keeps runs reproducible and makes cross-weight comparison straightforward.

---

## 2026-03-21 (automation update)

### Automated weight sweep script with semantic experiment folders

**File:**
- `EdgeCloudSim/scripts/laces/python/run_weight_sweep.py`

Added end-to-end automation for LACES weight experiments:

- Generates all valid weight combinations on simplex (default step `0.05`, total `231` combinations)
- Updates `default_config.properties` weights per run:
    - `laces_weight_latency`
    - `laces_weight_computation`
    - `laces_weight_data`
- Runs per-weight multiple iterations (default `8`)
- Restores original config file after sweep (including interruption cases)
- Extracts and stores per-iteration metrics from logs:
    - `service_time`
    - `failed_task`

#### Semantic output structure

```text
output/weight_tuning/
    <app>/
        <run_id>/
            run_manifest.json
            metrics_per_iteration.csv
            w1_0.50_w2_0.30_w3_0.20/
                metadata.json
                ite1.log
                ite2.log
                ...
                ite8.log
```

This gives each run explicit experiment context (app, objective, step, iteration policy) beyond simple folder names.

#### Multi-device sweep fix (200 -> 2000)

Updated Python automation to correctly handle logs that contain multiple device-count scenarios in a single iteration output (e.g., 200, 400, ..., 2000):

- `run_weight_sweep.py`
    - Now extracts metrics per scenario block, not only the last summary in log.
    - Adds `device_count` column to `metrics_per_iteration.csv`.
- `build_metrics_csv.py`
    - Now parses all scenario summaries in each `ite*.log` and includes `device_count`.
- `tune_weight_grid.py`
    - Added `--group-by` option in `sim` mode (e.g. `--group-by app,device_count`) so ranking can be done per app and per device scale.

This avoids collapsing 10 device-scale scenarios into a single row and makes weight selection robust for 200-2000 experiments.

---

## 2026-03-21 (recommended run plan)

### Suggested execution order: smoke run first, full sweep second

To reduce rerun cost and catch parsing/format issues early, use a phased workflow:

#### Phase 1: Smoke run (recommended)

Run a small subset first (example: first 5 weight combos, 2 iterations each):

```bash
cd /workspace/EdgeCloudSim/scripts/laces/python
python3 run_weight_sweep.py --app ar --step 0.05 --iterations 2 --limit 5 --compile
```

Validate:

- output folder structure
- `run_manifest.json`
- per-weight `metadata.json`
- `metrics_per_iteration.csv` contains `device_count`, `service_time`, `failed_task`

#### Phase 2: Full sweep

After smoke validation, run full settings (no `--limit`, 8 iterations):

```bash
python3 run_weight_sweep.py --app ar --step 0.05 --iterations 8 --compile
python3 run_weight_sweep.py --app health --step 0.05 --iterations 8 --compile
python3 run_weight_sweep.py --app info --step 0.05 --iterations 8 --compile
```

#### Ranking recommendation

Use simulation ranking grouped by app and device scale:

```bash
python3 tune_weight_grid.py \
    --mode sim \
    --input-csv <metrics.csv> \
    --iterations 8 \
    --group-by app,device_count \
    --output-csv ranked_by_app_device.csv
```

#### Scale reminder

With step `0.05`, combinations are `231`.
If each weight runs `8` iterations and each run includes device counts `200..2000` (10 scales),
total evaluated points are:

`231 * 8 * 10 = 18480`

This is why a smoke run is strongly recommended before full sweep.

---

## 2026-03-21 (runtime validation)

### Verified: single-app AR run now behaves correctly

Validated with run output:

- `scripts/laces/output/weight_tuning/ar/20260321_131145/w1_0.00_w2_0.00_w3_1.00/ite1.log`

Observed expected behavior:

- App-level summary now prints `Edge/Cloud/Mobile` (not just `Edge/Cloud`).
- Task placement is feasible and no longer collapses into invalid mobile-only execution.
- Metrics are now valid for this run:
    - `average service time: 1.333025 seconds`
    - `failed tasks: 333 / total tasks: 56179`
    - `completed tasks: 55846`

Notes:

- `NaN` can still appear for tiers that receive no tasks (for example cloud/mobile in an edge-only run), which is expected and not an error.
- The critical failure pattern where all tasks failed and service time was `NaN` has been resolved for this validated case.

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
