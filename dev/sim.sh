#!/usr/bin/env bash
# Drive the §7.7 SIM broadcast surface on the tablet through dev/nas.sh.
# Usage:
#   dev/sim.sh display <display.x> '<params json>'   inject a display command (JSON sent as base64)
#   dev/sim.sh cmd <cmd> [extra am-broadcast args]     e.g. dev/sim.sh cmd ptt --es value tap
#   dev/sim.sh diag                                    print the diag JSON line
#   dev/sim.sh log [n]                                 last n JARVIS_SIM log lines (default 10)
set -euo pipefail
cd "$(dirname "$0")/.."
BR="am broadcast -a dev.timlock.jarvis.SIM"
case "${1:-}" in
  display)
    b64=$(printf '%s' "${3:-{\}}" | base64 -w0)
    dev/nas.sh adb shell "$BR --es cmd display --es name $2 --es json_b64 $b64" >/dev/null
    sleep 0.5; dev/nas.sh adb logcat -d -s JARVIS_SIM | grep '"cmd":"display"' | tail -1 ;;
  cmd) c=$2; shift 2; dev/nas.sh adb shell "$BR --es cmd $c $*" >/dev/null ;;
  diag) dev/nas.sh adb shell "$BR --es cmd diag" >/dev/null; sleep 0.3
        dev/nas.sh adb logcat -d -s JARVIS_SIM | grep '"cmd":"diag"' | tail -1 ;;
  log) dev/nas.sh adb logcat -d -s JARVIS_SIM | tail -"${2:-10}" ;;
  *) sed -n '2,7p' "$0"; exit 2 ;;
esac
