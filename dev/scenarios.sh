#!/usr/bin/env bash
# Run sim scenarios unattended on the tablet and summarize their expect results.
# Usage: dev/scenarios.sh [id ...]   (default: every bundled scenario except soak_perf)
# Each scenario: logcat cleared, started with auto PTT, polled until scenario_done (timeout 150 s).
set -uo pipefail
cd "$(dirname "$0")/.."
ALL="hello_short weather_mid_reply card_while_thinking planning_outline long_list panel_flood sticky_overflow multi_turn barge_in_long slow_think error_offline_mid_reply token_revoked admission_fails send_failure ambient_push_timer invalid_actions call_takeover"
IDS="${*:-$ALL}"
mkdir -p dev/out
summary=dev/out/scenarios-summary.txt; : > "$summary"
for id in $IDS; do
  dev/nas.sh adb logcat -c >/dev/null 2>&1
  dev/sim.sh cmd hangup; dev/sim.sh cmd preset --es value clear_all; sleep 1
  dev/sim.sh cmd scenario --es id "$id" --el auto_ptt_ms 500
  done_line=""
  for _ in $(seq 1 30); do
    sleep 5
    log=$(dev/nas.sh adb logcat -d -s JARVIS_SIM 2>/dev/null)
    done_line=$(printf '%s\n' "$log" | grep '"cmd":"scenario_done"' | tail -1)
    [ -n "$done_line" ] && break
  done
  printf '%s\n' "$log" > "dev/out/scenario-$id.log"
  fails=$(printf '%s\n' "$log" | grep '"cmd":"expect"' | grep '"ok":false' | sed 's/.*"error":/  /')
  if [ -z "$done_line" ]; then res="TIMEOUT"; else res=$(printf '%s' "$done_line" | sed -E 's/.*"asserts":([0-9]+),"failed":([0-9]+).*/asserts=\1 failed=\2/'); fi
  echo "$id: $res" | tee -a "$summary"
  [ -n "$fails" ] && echo "$fails" | tee -a "$summary"
done
dev/sim.sh cmd stop
