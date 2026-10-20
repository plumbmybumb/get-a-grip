#!/usr/bin/env bash
# SPDX-License-Identifier: MPL-2.0
# Original contributions Copyright 2026 Nuri Bruner.
#
# iPhone Duo pose survey (docs/IPHONE_DUO.md, "Simulator first look").
#
#   DISPLAY_ID=1 scripts/duo_survey.sh <duo-udid> closed-portrait          # outer display
#   DISPLAY_ID=3 scripts/duo_survey.sh <duo-udid> open-portrait [screens]  # inner display
#
# Launches the already-installed DEBUG build (`SIM_UDID=<udid> ./build.sh run` first)
# with the house preview arguments per screen and screenshots each into
# docs/screenshots/duo/<screen>-<pose>.png. The POSE is yours to set by hand in
# Device Hub before running — no simctl/devicectl command folds the Duo (2026-10-20).
# `simctl io screenshot` defaults to the INNER display, which is dark while the phone
# is closed, so the display id is explicit: 1 = outer (LCD), 3 = inner (LCD-1).
# Screens: today runner runner-preview builder builder-grip history maxes settings.
set -uo pipefail
UDID="$1"; POSE="$2"; shift 2
OUT="$(cd "$(dirname "$0")/.." && pwd)/docs/screenshots/duo"
BUNDLE="run.nuri.doigt"
mkdir -p "$OUT"
SCREENS=("$@"); [ ${#SCREENS[@]} -eq 0 ] && SCREENS=(today runner builder-grip history maxes settings)
shot() { # name, wait-seconds, args...
  local name="$1"; local wait="$2"; shift 2
  xcrun simctl terminate "$UDID" "$BUNDLE" 2>/dev/null || true
  sleep 1
  xcrun simctl launch "$UDID" "$BUNDLE" -mockDevice "$@" >/dev/null || { echo "launch failed: $name"; return; }
  sleep "$wait"
  xcrun simctl io "$UDID" screenshot --display="${DISPLAY_ID:-1}" --type=png "$OUT/$name-$POSE.png" >/dev/null 2>&1
  python3 -c "import struct,sys; f=open(sys.argv[1],'rb'); f.read(16); w,h=struct.unpack('>II', f.read(8)); print(f'{sys.argv[1].split(\"/\")[-1]}: {w}x{h} px = {w/3:g}x{h/3:g} pt @3x')" "$OUT/$name-$POSE.png"
}
for s in "${SCREENS[@]}"; do
  case "$s" in
    today)        shot today 7 -seedTwoRoutines -seedHistory -tab 0 -dumpInteractions ;;
    runner)       shot runner 12 -seedTwoRoutines -seedHistory -startFirstRoutine ;;
    runner-preview) shot runner-preview 6 -seedTwoRoutines -previewRunnerWorking -previewRunnerTarget ;;
    builder)      shot builder 6 -seedTwoRoutines -seedHistory -previewBuilder ;;
    builder-grip) shot builder-grip 7 -seedTwoRoutines -seedHistory -previewBuilder -previewGripPanel ;;
    history)      shot history 6 -seedTwoRoutines -seedHistory -tab 1 ;;
    maxes)        shot maxes 6 -seedTwoRoutines -seedHistory -tab 2 ;;
    settings)     shot settings 6 -seedTwoRoutines -seedHistory -tab 3 ;;
    *) echo "unknown screen $s" ;;
  esac
done
# Point sizes as UIKit reports them (window frame), from the -dumpInteractions tree.
C=$(xcrun simctl get_app_container "$UDID" "$BUNDLE" data 2>/dev/null)
[ -n "$C" ] && [ -f "$C/Documents/interactions.txt" ] && { echo "--- window frames ($POSE):"; grep "^WINDOW" "$C/Documents/interactions.txt"; }
