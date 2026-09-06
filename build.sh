#!/usr/bin/env bash
#
# Doigt — the ONE canonical build/run command.
#   ./build.sh        -> regenerate project + build to the pinned simulator
#   ./build.sh run    -> the above, then boot + install + launch on the simulator
#   ./build.sh test   -> regenerate project + run the XCTest suite on the pinned simulator
#
# It prints ONLY actionable errors/warnings + the final status. The full,
# unfiltered xcodebuild log is always at build/last-build.log for debugging.
#
# House convention: quote every path (sibling projects live under paths with
# spaces, and this script gets copied between them).
#
# Pins: Xcode 26.6 · iOS deployment target 26.0 · simulator iPhone 17 Pro / iOS 26.5.
# DEVELOPER_DIR is set here in case the machine's active dir is CommandLineTools
# rather than Xcode (avoids needing sudo).
#
# NOTE: CoreBluetooth does NOT work in the Simulator. Simulator runs use the mock
# device (`./build.sh run` passes -mockDevice); real hardware verification happens
# from Xcode against a team-signed device build.
set -uo pipefail

export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"

# Override SIM_UDID to choose a device; otherwise select an installed iOS 26+ iPhone.
SIM_UDID="${SIM_UDID:-}"
if [ -z "$SIM_UDID" ]; then
  SIM_UDID=$(xcrun simctl list devices available -j | python3 -c '
import json,sys,re
items=[]
for runtime, devices in json.load(sys.stdin)["devices"].items():
    match=re.search(r"iOS-(\d+)-(\d+)",runtime)
    if match and int(match[1]) >= 26:
        for d in devices:
            if d.get("isAvailable") and d["name"].startswith("iPhone"):
                items.append((int(match[1]),int(match[2]),d["name"],d["udid"]))
print(max(items)[-1] if items else "")')
fi
if [ -z "$SIM_UDID" ]; then
  echo "Install an iOS 26 or newer iPhone simulator in Xcode, or set SIM_UDID."
  exit 1
fi
SCHEME="Doigt"
BUNDLE_ID="run.nuri.doigt"
PROJECT="Doigt.xcodeproj"
DERIVED="build/DerivedData"
LOG="build/last-build.log"

cd "$(dirname "$0")"
mkdir -p build

# 1. Keep the .xcodeproj in sync with project.yml (never hand-edit the pbxproj).
if ! xcodegen generate --quiet --spec "${XCODEGEN_SPEC:-project.yml}"; then
  echo "❌ xcodegen failed — check project.yml"
  exit 1
fi

# 1b. `test` verb: run the XCTest suite on the pinned simulator, then exit. Full log
# goes to build/last-test.log; only failures + the summary are surfaced.
if [ "${1:-}" = "test" ]; then
  TEST_LOG="build/last-test.log"
  xcodebuild \
    -project "$PROJECT" \
    -scheme "$SCHEME" \
    -configuration Debug \
    -destination "platform=iOS Simulator,id=$SIM_UDID" \
    -derivedDataPath "$DERIVED" \
    CODE_SIGN_IDENTITY="-" CODE_SIGNING_REQUIRED=NO \
    test > "$TEST_LOG" 2>&1
  TSTATUS=$?

  grep -E "error:|warning:|fatal error|ld: |Undefined symbol|The following build commands failed|Test Suite '.*' (passed|failed)|Executed [0-9]+ test|\*\* TEST (SUCCEEDED|FAILED)" "$TEST_LOG" \
    | grep -vE "appintentsmetadataprocessor.*Metadata extraction skipped" \
    | sed -E 's#'"$PWD"'/##g' \
    || true

  if [ $TSTATUS -ne 0 ]; then
    echo "❌ TESTS FAILED (status $TSTATUS) — full log: $TEST_LOG"
    exit $TSTATUS
  fi
  echo "✅ TESTS PASSED"
  exit 0
fi

# 2. Build. Ad-hoc sign for the simulator (no team needed). NOTE: ad-hoc signing
# strips the iCloud entitlements, so CloudKit-backed SwiftData falls back to the
# local-only store in simulator builds (the app handles this — see DoigtApp.swift).
# Real sync testing needs a team-signed device build.
xcodebuild \
  -project "$PROJECT" \
  -scheme "$SCHEME" \
  -configuration Debug \
  -destination "id=$SIM_UDID" \
  -derivedDataPath "$DERIVED" \
  CODE_SIGN_IDENTITY="-" CODE_SIGNING_REQUIRED=NO \
  build > "$LOG" 2>&1
STATUS=$?

# 3. Surface only the actionable lines.
grep -E "error:|warning:|fatal error|ld: |Undefined symbol|The following build commands failed|BUILD SUCCEEDED|BUILD FAILED" "$LOG" \
  | grep -vE "appintentsmetadataprocessor.*Metadata extraction skipped" \
  | sed -E 's#'"$PWD"'/##g' \
  || true

if [ $STATUS -ne 0 ]; then
  echo "❌ BUILD FAILED (status $STATUS) — full log: $LOG"
  exit $STATUS
fi
echo "✅ BUILD SUCCEEDED"

# 4. Optional: run on the simulator. Always with the mock device — the Simulator
# has no Bluetooth stack at all, so a real client would sit at "scanning" forever.
if [ "${1:-}" = "run" ]; then
  shift
  open -a Simulator || { echo "❌ Could not open Simulator" >&2; exit 1; }
  xcrun simctl boot "$SIM_UDID" 2>/dev/null || true
  APP_PATH="$DERIVED/Build/Products/Debug-iphonesimulator/$SCHEME.app"
  BUNDLE_ID=$(/usr/libexec/PlistBuddy -c "Print CFBundleIdentifier" "$APP_PATH/Info.plist") \
    || { echo "❌ Could not read the built app's bundle identifier" >&2; exit 1; }
  if [ -z "$BUNDLE_ID" ]; then
    echo "❌ The built app has no bundle identifier" >&2
    exit 1
  fi
  xcrun simctl bootstatus "$SIM_UDID" -b >/dev/null \
    || { echo "❌ Simulator $SIM_UDID did not finish booting" >&2; exit 1; }
  # Terminate any running instance first so launch starts the freshly-installed
  # binary (otherwise simctl just foregrounds the old, still-running process).
  xcrun simctl terminate "$SIM_UDID" "$BUNDLE_ID" 2>/dev/null || true
  xcrun simctl install "$SIM_UDID" "$APP_PATH" \
    || { echo "❌ Simulator install failed" >&2; exit 1; }
  xcrun simctl launch "$SIM_UDID" "$BUNDLE_ID" -mockDevice "$@" >/dev/null \
    || { echo "❌ Simulator launch failed" >&2; exit 1; }
  echo "🚀 launched $BUNDLE_ID (mock device) on simulator $SIM_UDID"
fi
