#!/usr/bin/env bash
# NAS build/install pipeline (Gradle cannot run on the dev VM; see BUILD_BRIEF).
# Usage:
#   dev/nas.sh build            sync source to NAS + gradle assembleDebug (log: dev/out/build.log)
#   dev/nas.sh test             sync + gradle testDebugUnitTest
#   dev/nas.sh gradle <tasks>   sync + arbitrary gradle tasks
#   dev/nas.sh install          copy APK to adb-tools, adb install -r, launch MainActivity
#   dev/nas.sh adb <args...>    run one adb command on the tablet (output printed)
#   dev/nas.sh shot <out.png>   screencap the tablet and copy the PNG to <out.png> locally
#   dev/nas.sh start [extras]   am start MainActivity with optional extra args (e.g. --es jarvis.mode sim)
set -euo pipefail
cd "$(dirname "$0")/.."
SSH=(ssh -F /home/hatch/.ssh/config timothy@tim-nas)
# JARVIS_REMOTE_SUFFIX lets parallel worktrees build in separate NAS dirs (e.g. "-wt2").
REMOTE=/volume2/docker/android-build/jarvis${JARVIS_REMOTE_SUFFIX:-}
TOOLS=/volume2/docker/adb-tools
PKG=dev.timlock.jarvis
mkdir -p dev/out

sync() {
  tar --exclude=.git --exclude='*/build' --exclude=.gradle --exclude=dev/out --exclude=.claude --exclude=local.properties -czf - . \
    | "${SSH[@]}" "mkdir -p $REMOTE && find $REMOTE -type d \( -name build -o -name .gradle \) -prune -o -type d -name src -prune -exec rm -rf {} + 2>/dev/null; tar -xzf - -C $REMOTE && echo 'sdk.dir=/opt/android-sdk' > $REMOTE/local.properties"
}

gradle_run() {
  sync
  set +e
  "${SSH[@]}" "docker run --rm -v $REMOTE:/work -v android-gradle-cache:/root/.gradle -v /volume2/docker/android-build/android-home:/root/.android android-build:35-py311 gradle --console=plain $*" > dev/out/build.log 2>&1
  rc=$?
  set -e
  grep -E '^e: |error:|FAILED|BUILD SUCCESSFUL|BUILD FAILED|What went wrong' -A3 dev/out/build.log | head -80
  echo "gradle exit=$rc (full log: dev/out/build.log)"
  return $rc
}

adb_sh() {
  # $1 = shell snippet run inside the adb container, with $ADB available
  "${SSH[@]}" "DEV=\$(lsusb | grep 04e8:685d); BUS=\$(echo \$DEV | sed -E 's/Bus ([0-9]+) Device ([0-9]+).*/\1/'); NUM=\$(echo \$DEV | sed -E 's/Bus ([0-9]+) Device ([0-9]+).*/\2/'); docker run --rm -v $TOOLS:/tools --device=/dev/bus/usb/\$BUS/\$NUM debian:12-slim sh -c 'ADB=/tools/platform-tools/adb; \$ADB wait-for-device >/dev/null 2>&1; $1'"
}

case "${1:-}" in
  build) gradle_run assembleDebug ;;
  test) gradle_run testDebugUnitTest ;;
  gradle) shift; gradle_run "$@" ;;
  install)
    "${SSH[@]}" "cp $REMOTE/app/build/outputs/apk/debug/app-debug.apk $TOOLS/jarvis-debug.apk"
    adb_sh "\$ADB install -r /tools/jarvis-debug.apk && \$ADB shell am start -n $PKG/.MainActivity" ;;
  start) shift; adb_sh "\$ADB shell am start -n $PKG/.MainActivity $*" ;;
  adb) shift; adb_sh "\$ADB $*" ;;
  shot)
    out="${2:?output path}"
    adb_sh "\$ADB shell screencap -p /sdcard/jarvis-shot.png && \$ADB pull /sdcard/jarvis-shot.png /tools/jarvis-shot.png >/dev/null"
    "${SSH[@]}" "cat $TOOLS/jarvis-shot.png" > "$out"
    ls -l "$out" ;;
  *) sed -n '2,10p' "$0"; exit 2 ;;
esac
