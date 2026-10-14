#!/usr/bin/env bash
#
# LatvianTube — build, install, and run on an Android TV emulator.
#
# Everything the toolchain needs lives inside the project's .cache/toolchain
# directory, so nothing is installed system-wide and no Android Studio is
# required. Override the location with LATVIANTUBE_TOOLCHAIN if you installed the
# SDK somewhere else (e.g. your own ~/Library/Android/sdk).
#
# Usage:
#   ./run-tv.sh                 # build, install, launch (opens an emulator window)
#   ./run-tv.sh --no-build      # skip Gradle; reuse the existing APK
#   LATVIANTUBE_HEADLESS=1 ./run-tv.sh   # no window (for scripts/CI)
#   ./run-tv.sh --kill          # just stop any running emulator and exit
#
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# The toolchain sits beside the project, in the workspace's .cache directory.
TOOLCHAIN="${LATVIANTUBE_TOOLCHAIN:-$(cd "$PROJECT_DIR/.." && pwd)/.cache/toolchain}"
AVD_NAME="${LATVIANTUBE_AVD:-latviantube_tv}"
EMULATOR_PORT="${LATVIANTUBE_PORT:-5554}"
DEVICE="emulator-$EMULATOR_PORT"
APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
ACTIVITY="lv.latviantube/.ui.browse.BrowseActivity"

# ---------------------------------------------------------------------------
# Environment. HOME is redirected deliberately: adb and the emulator otherwise
# write to ~/.android, and keeping every path inside the project means this
# script cannot disturb an existing Android Studio install.
# ---------------------------------------------------------------------------
export JAVA_HOME="$TOOLCHAIN/jdk17"
export ANDROID_HOME="$TOOLCHAIN/android-sdk"
export ANDROID_USER_HOME="$TOOLCHAIN/android-user-home"
export ANDROID_AVD_HOME="$TOOLCHAIN/android-avd"
export GRADLE_USER_HOME="$TOOLCHAIN/gradle-home"
export HOME="$TOOLCHAIN/home"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$JAVA_HOME/bin:$PATH"

mkdir -p "$ANDROID_USER_HOME" "$ANDROID_AVD_HOME" "$GRADLE_USER_HOME" "$HOME"

die() { echo "error: $*" >&2; exit 1; }

stop_emulator() {
  if adb devices 2>/dev/null | grep -q "^$DEVICE"; then
    echo "==> stopping $DEVICE"
    adb -s "$DEVICE" emu kill >/dev/null 2>&1 || true
    sleep 5
  fi
}

# ---------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------
[ -x "$JAVA_HOME/bin/java" ] || die "no JDK at $JAVA_HOME — set LATVIANTUBE_TOOLCHAIN"
[ -x "$ANDROID_HOME/emulator/emulator" ] || die "no emulator at $ANDROID_HOME/emulator"
[ -d "$ANDROID_AVD_HOME/$AVD_NAME.avd" ] || die "AVD '$AVD_NAME' not found in $ANDROID_AVD_HOME

Create it with:
  \$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager create avd \\
      -n $AVD_NAME -k 'system-images;android-34;android-tv;arm64-v8a' -d tv_1080p"

if [ "${1:-}" = "--kill" ]; then
  stop_emulator
  echo "done."
  exit 0
fi

# ---------------------------------------------------------------------------
# 1. Build
# ---------------------------------------------------------------------------
if [ "${1:-}" != "--no-build" ]; then
  echo "==> building debug APK"
  ( cd "$PROJECT_DIR" && ./gradlew assembleDebug --console=plain -q )
fi
[ -f "$APK" ] || die "APK not found at $APK (run without --no-build)"
echo "==> APK: $(ls -lh "$APK" | awk '{print $5}')"

# ---------------------------------------------------------------------------
# 2. Boot the emulator
# ---------------------------------------------------------------------------
if adb devices 2>/dev/null | grep -q "^$DEVICE[[:space:]]*device"; then
  echo "==> $DEVICE already running, reusing it"
else
  stop_emulator
  echo "==> starting Android TV emulator ($AVD_NAME)"

  # Two explicit branches rather than an array of flags. macOS ships bash 3.2,
  # where expanding an empty array under `set -u` aborts with
  # "WINDOW_FLAGS[@]: unbound variable". Because the launch is backgrounded, that
  # failure killed only the subshell, so the script went on to wait out the full
  # boot timeout for an emulator that had never started.
  if [ -n "${LATVIANTUBE_HEADLESS:-}" ]; then
    nohup emulator -avd "$AVD_NAME" -port "$EMULATOR_PORT" \
      -no-boot-anim -no-snapshot-save -no-window -gpu swiftshader_indirect \
      > "$TOOLCHAIN/emulator.log" 2>&1 &
  else
    nohup emulator -avd "$AVD_NAME" -port "$EMULATOR_PORT" \
      -no-boot-anim -no-snapshot-save \
      > "$TOOLCHAIN/emulator.log" 2>&1 &
  fi
  EMULATOR_PID=$!
  echo "    (pid $EMULATOR_PID, log: $TOOLCHAIN/emulator.log)"

  # Fail fast. Without this, a dead emulator is indistinguishable from a slow
  # boot and costs the full timeout before reporting anything useful.
  sleep 10
  if ! kill -0 "$EMULATOR_PID" 2>/dev/null; then
    echo "" >&2
    echo "--- last lines of $TOOLCHAIN/emulator.log ---" >&2
    tail -n 20 "$TOOLCHAIN/emulator.log" >&2 || true
    die "emulator exited immediately; see the log above"
  fi
fi

echo "==> waiting for boot (first cold boot can take 2-4 minutes)"
for _ in $(seq 1 120); do
  if [ "$(adb -s "$DEVICE" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
    echo "==> booted"
    break
  fi
  sleep 5
done
[ "$(adb -s "$DEVICE" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] \
  || die "emulator did not finish booting — see $TOOLCHAIN/emulator.log"
adb -s "$DEVICE" shell settings put global window_animation_scale 0 >/dev/null 2>&1 || true

# ---------------------------------------------------------------------------
# 3. Install and launch
# ---------------------------------------------------------------------------
echo "==> installing"
adb -s "$DEVICE" install -r "$APK" | tail -1

echo "==> launching LatvianTube"
adb -s "$DEVICE" shell am force-stop lv.latviantube >/dev/null 2>&1 || true
adb -s "$DEVICE" shell am start -n "$ACTIVITY" >/dev/null

cat <<EOF

================================================================
LatvianTube is running on $DEVICE
================================================================
The home screen loads 8 rows from YouTube, so give it ~30s on
first launch (later launches are instant, served from cache).

Drive it with the mouse, or with the arrow keys:
  arrow keys / click   move focus, open a card
  ESC                  back
  Ctrl+C here          stop waiting (emulator keeps running)

Useful commands:
  adb -s $DEVICE exec-out screencap -p > shot.png     # screenshot
  adb -s $DEVICE logcat | grep -E "BrowseFragment|SearchFragment"
  ./run-tv.sh --kill                                  # stop the emulator
================================================================
EOF
