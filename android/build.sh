#!/usr/bin/env bash
# Android build entry point — the twin of the iOS ./build.sh.
#
#   ./android/build.sh engine     # :engine tests — needs only a JDK
#   ./android/build.sh test       # every module's tests
#   ./android/build.sh app        # assemble the debug APK (needs the Android SDK)
#   ./android/build.sh release    # assemble the R8-shrunk release APK (debug-signed for now)
#   ./android/build.sh devices    # what adb can see
#   ./android/build.sh install    # install the debug APK on the connected phone/emulator
#   ./android/build.sh run        # install + launch
#   ./android/build.sh <gradle…>  # anything else is passed to gradlew
#
# JAVA_HOME resolution: an explicit JAVA_HOME wins; otherwise Android Studio's bundled
# JBR (the JDK AGP is tested with); otherwise Homebrew's openjdk (Gradle 9.7 runs on
# JDK 17–26). The SDK comes from local.properties (sdk.dir), written once per machine.
set -euo pipefail
cd "$(dirname "$0")"

if [[ -z "${JAVA_HOME:-}" ]]; then
  for candidate in \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
    /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
    /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home; do
    if [[ -x "$candidate/bin/java" ]]; then export JAVA_HOME="$candidate"; break; fi
  done
fi
if [[ -z "${JAVA_HOME:-}" ]] && command -v java >/dev/null 2>&1; then
  JAVA_HOME="$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*java.home = //p')"
  export JAVA_HOME
fi
[[ -n "${JAVA_HOME:-}" ]] || { echo "No JDK found. Install Android Studio or brew install openjdk, or set JAVA_HOME." >&2; exit 1; }

SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK_DIR" && -f local.properties ]]; then
  SDK_DIR="$(sed -n 's/^sdk.dir=//p' local.properties)"
fi
ADB="${SDK_DIR:-$HOME/Library/Android/sdk}/platform-tools/adb"
APP_ID="run.nuri.getagrip"

case "${1:-test}" in
  test)    ./gradlew test ;;
  engine)  ./gradlew :engine:test ;;
  app)     ./gradlew :app:assembleDebug ;;
  release) ./gradlew :app:assembleRelease ;;
  devices) "$ADB" devices -l ;;
  install) ./gradlew :app:installDebug ;;
  run)     ./gradlew :app:installDebug && "$ADB" shell am start -n "$APP_ID/.MainActivity" ;;
  *)       ./gradlew "$@" ;;
esac
