#!/usr/bin/env bash
#
# Get a Grip — the ONE canonical build/run command.
#   ./build.sh        -> regenerate project + build to the chosen simulator
#   ./build.sh run    -> the above, then boot + install + launch on the simulator
#   ./build.sh test   -> regenerate project + run the XCTest suite (DoigtTests)
#   ./build.sh uitest -> regenerate project + run the XCUITest suite (DoigtUITests)
#   ./build.sh uitest WeightUnitsUITests           -> one class
#   ./build.sh uitest WeightUnitsUITests/testFoo   -> one test
#   ./build.sh watch  -> regenerate project + build the watch app for a watchOS simulator
#   ./build.sh watch-run -> the above, then boot + install + launch it WITH -mockDevice
#   SIM_UDID=<an iPad's udid> ./build.sh run   -> the iPad build, same verbs
#
# It prints ONLY actionable errors/warnings + the final status. The full,
# unfiltered xcodebuild log is always at build/last-build.log for debugging.
#
# House convention: quote every path (sibling projects live under paths with
# spaces, and this script gets copied between them).
#
# Requires Xcode 26 or newer; the iOS deployment target is 26.0. The SIMULATOR is not
# pinned — the newest installed iOS 26+ iPhone is chosen at run time, so a machine that
# has only the current runtime still builds. SIM_UDID overrides that choice (an iPad's
# udid builds and runs the iPad layouts); the watch verbs pick the newest watchOS 26+
# Apple Watch the same way, and WATCH_UDID overrides it.
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

# 1c. `uitest` verb: the XCUITest suite in UITests/, which is its own target and its own
# scheme (DoigtUITests) because a UI test launches the app as a separate process rather
# than linking it. A second argument narrows the run to one class or one test; xcodebuild
# wants it as `-only-testing:<target>/<class>[/<test>]`, so only the tail is typed here.
# Full log goes to build/last-uitest.log; only failures + the summary are surfaced.
if [ "${1:-}" = "uitest" ]; then
  UITEST_LOG="build/last-uitest.log"
  # `${VAR:+"$VAR"}` expands to NOTHING when the filter is empty, instead of to an empty
  # argument that xcodebuild would reject.
  ONLY=""
  [ -n "${2:-}" ] && ONLY="-only-testing:DoigtUITests/$2"
  xcodebuild \
    -project "$PROJECT" \
    -scheme "DoigtUITests" \
    -configuration Debug \
    -destination "platform=iOS Simulator,id=$SIM_UDID" \
    -derivedDataPath "$DERIVED" \
    CODE_SIGN_IDENTITY="-" CODE_SIGNING_REQUIRED=NO \
    ${ONLY:+"$ONLY"} \
    test > "$UITEST_LOG" 2>&1
  USTATUS=$?

  grep -E "error:|warning:|fatal error|ld: |Undefined symbol|The following build commands failed|Test Suite '.*' (passed|failed)|Executed [0-9]+ test|\*\* TEST (SUCCEEDED|FAILED)" "$UITEST_LOG" \
    | grep -vE "appintentsmetadataprocessor.*Metadata extraction skipped" \
    | sed -E 's#'"$PWD"'/##g' \
    || true

  if [ $USTATUS -ne 0 ]; then
    echo "❌ UI TESTS FAILED (status $USTATUS) — full log: $UITEST_LOG"
    exit $USTATUS
  fi
  echo "✅ UI TESTS PASSED"
  exit 0
fi

# 1d. `watch` / `watch-run` verbs: build the watch app for a watchOS simulator, and
# optionally boot + install + launch it there. Always with the mock gauge — the watch
# Simulator has no Bluetooth stack either. Override WATCH_UDID to pick a device.
if [ "${1:-}" = "watch" ] || [ "${1:-}" = "watch-run" ]; then
  WATCH_UDID="${WATCH_UDID:-}"
  if [ -z "$WATCH_UDID" ]; then
    WATCH_UDID=$(xcrun simctl list devices available -j | python3 -c '
import json,sys,re
items=[]
for runtime, devices in json.load(sys.stdin)["devices"].items():
    match=re.search(r"watchOS-(\d+)-(\d+)",runtime)
    if match and int(match[1]) >= 26:
        for d in devices:
            if d.get("isAvailable") and d["name"].startswith("Apple Watch"):
                items.append((int(match[1]),int(match[2]),d["name"],d["udid"]))
print(max(items)[-1] if items else "")')
  fi
  if [ -z "$WATCH_UDID" ]; then
    echo "Install a watchOS 26 or newer Apple Watch simulator in Xcode, or set WATCH_UDID."
    exit 1
  fi
  WATCH_LOG="build/last-watch-build.log"
  xcodebuild \
    -project "$PROJECT" \
    -scheme "DoigtWatch" \
    -configuration Debug \
    -destination "platform=watchOS Simulator,id=$WATCH_UDID" \
    -derivedDataPath "$DERIVED" \
    CODE_SIGN_IDENTITY="-" CODE_SIGNING_REQUIRED=NO \
    build > "$WATCH_LOG" 2>&1
  WSTATUS=$?
  grep -E "error:|warning:|fatal error|ld: |Undefined symbol|The following build commands failed|BUILD SUCCEEDED|BUILD FAILED" "$WATCH_LOG" \
    | grep -vE "appintentsmetadataprocessor.*Metadata extraction skipped" \
    | sed -E 's#'"$PWD"'/##g' \
    || true
  if [ $WSTATUS -ne 0 ]; then
    echo "❌ WATCH BUILD FAILED (status $WSTATUS) — full log: $WATCH_LOG"
    exit $WSTATUS
  fi
  echo "✅ WATCH BUILD SUCCEEDED"
  if [ "${1:-}" = "watch-run" ]; then
    shift
    # By path: Launch Services does not always know the Simulator by name.
  open -a "$DEVELOPER_DIR/Applications/Simulator.app" 2>/dev/null || open -a Simulator 2>/dev/null \
    || echo "⚠️  Could not open the Simulator app — continuing headless (simctl needs no window)"
    xcrun simctl boot "$WATCH_UDID" 2>/dev/null || true
    WATCH_APP="$DERIVED/Build/Products/Debug-watchsimulator/DoigtWatch.app"
    WATCH_BUNDLE_ID=$(/usr/libexec/PlistBuddy -c "Print CFBundleIdentifier" "$WATCH_APP/Info.plist") \
      || { echo "❌ Could not read the built watch app's bundle identifier" >&2; exit 1; }
    xcrun simctl bootstatus "$WATCH_UDID" -b >/dev/null \
      || { echo "❌ Simulator $WATCH_UDID did not finish booting" >&2; exit 1; }
    xcrun simctl terminate "$WATCH_UDID" "$WATCH_BUNDLE_ID" 2>/dev/null || true
    xcrun simctl install "$WATCH_UDID" "$WATCH_APP" \
      || { echo "❌ Watch simulator install failed" >&2; exit 1; }
    xcrun simctl launch "$WATCH_UDID" "$WATCH_BUNDLE_ID" -mockDevice "$@" >/dev/null \
      || { echo "❌ Watch simulator launch failed" >&2; exit 1; }
    echo "🚀 launched $WATCH_BUNDLE_ID (mock device) on watch simulator $WATCH_UDID"
  fi
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
  # By path: Launch Services does not always know the Simulator by name.
  open -a "$DEVELOPER_DIR/Applications/Simulator.app" 2>/dev/null || open -a Simulator 2>/dev/null \
    || echo "⚠️  Could not open the Simulator app — continuing headless (simctl needs no window)"
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
