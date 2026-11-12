#!/usr/bin/env bash
# Perf/soak sampler (M9, spec §3.6). Samples the running app on the tablet every INTERVAL seconds for MINUTES:
# app PSS (MB), app CPU % (top), thermal status, and the last JARVIS_PERF line (fps/p50/p95/tier/particles).
# Usage: dev/soak.sh <minutes> [interval_s] [scenario_id]   -> dev/out/soak-<ts>.csv (+ summary on stdout)
# If scenario_id is given (sim mode), it is restarted whenever the previous run has finished.
set -euo pipefail
cd "$(dirname "$0")/.."
MIN=${1:?minutes}; INT=${2:-60}; SCN=${3:-}
PKG=dev.timlock.jarvis
OUT=dev/out/soak-$(date +%Y%m%d-%H%M%S).csv
mkdir -p dev/out
echo "t_s,pss_mb,cpu_pct,thermal,fps,p50,p95,cpu_ms,cpu_p95,wait_ms,tier,particles" > "$OUT"
end=$(( $(date +%s) + MIN * 60 )); t0=$(date +%s)
while [ "$(date +%s)" -lt "$end" ]; do
  if [ -n "$SCN" ]; then
    dev/nas.sh adb shell "am broadcast -a dev.timlock.jarvis.SIM --es cmd diag" >/dev/null 2>&1 || true
    if ! dev/nas.sh adb logcat -d -s JARVIS_SIM 2>/dev/null | grep '"cmd":"diag"' | tail -1 | grep -q "\"id\":\"$SCN\""; then
      dev/nas.sh adb shell "am broadcast -a dev.timlock.jarvis.SIM --es cmd scenario --es id $SCN --el auto_ptt_ms 1500" >/dev/null 2>&1 || true
    fi
  fi
  raw=$(dev/nas.sh adb shell "dumpsys meminfo $PKG | grep 'TOTAL PSS'; top -b -n 1 | grep $PKG | head -1; dumpsys thermalservice | grep -m1 'Thermal Status'; logcat -d -s JARVIS_PERF | grep fps | tail -1" 2>/dev/null || true)
  pss=$(echo "$raw" | awk '/TOTAL PSS/ {printf "%.0f", $3/1024; exit}')
  cpu=$(echo "$raw" | awk -v p=$PKG '$0 ~ p && $9 ~ /^[0-9.]+$/ {print $9; exit}')
  th=$(echo "$raw" | awk -F': *' '/Thermal Status/ {print $2; exit}')
  perf=$(echo "$raw" | grep -o '{"fps".*}' | tail -1)
  j() { echo "$perf" | grep -o "\"$1\":[^,}]*" | head -1 | cut -d: -f2 | tr -d '"'; }
  echo "$(( $(date +%s) - t0 )),${pss:-},${cpu:-},${th:-},$(j fps),$(j p50),$(j p95),$(j cpuMs),$(j cpuP95),$(j waitMs),$(j tier),$(j particles)" | tee -a "$OUT"
  sleep "$INT"
done
echo "saved $OUT"
