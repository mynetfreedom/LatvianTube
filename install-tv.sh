#!/usr/bin/env bash
# Build (optional), connect to one Android TV over the LAN, install, and launch.
# Uses the existing project toolchain when available. Compatible with macOS bash 3.2.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLCHAIN="${LATVIANTUBE_TOOLCHAIN:-$PROJECT_DIR/.cache/toolchain}"
if [ -z "${LATVIANTUBE_TOOLCHAIN:-}" ] && [ ! -d "$TOOLCHAIN" ]; then
  for candidate in "$PROJECT_DIR/../.cache/toolchain" "$PROJECT_DIR/../FirmenD/.cache/toolchain"; do
    if [ -d "$candidate/android-sdk" ]; then TOOLCHAIN="$candidate"; break; fi
  done
fi
TV_ADDRESS=""
PAIR_ADDRESS=""
APK="$PROJECT_DIR/app/build/outputs/apk/debug/LatvianTube_v.1.1.apk"
BUILD=1
LAUNCH=1
SERVER_PORT="${LATVIANTUBE_ADB_SERVER_PORT:-5037}"

usage() {
    cat <<'EOF'
Usage: ./install-tv.sh TV_IP[:PORT] [--no-build] [--no-launch]
       ./install-tv.sh TV_IP:CONNECT_PORT --pair TV_IP:PAIR_PORT [--no-build]

Examples (replace the sample IP with your TV's actual address):
  ./install-tv.sh 192.168.50.91:5555              Build, install, and launch
  ./install-tv.sh 192.168.50.91:5555 --no-build   Install the existing APK (fastest)

The port defaults to 5555. Your TV must already allow network ADB debugging.
Keep the TV and computer on the same network and accept the TV's debugging prompt.
For Wireless debugging with a pairing code, use --pair with the separate pairing port.

Options:
  --no-build        Reuse app/build/outputs/apk/debug/LatvianTube_v.1.1.apk
  --no-launch       Install without opening the app
  --pair IP:PORT    Pair interactively before connecting (enter the code from the TV)
  -h, --help        Show this help

Environment overrides: LATVIANTUBE_TV, LATVIANTUBE_TOOLCHAIN, LATVIANTUBE_ADB,
                       LATVIANTUBE_ADB_SERVER_PORT (local ADB server, default 5037).
EOF
}

die() { printf 'error: %s\n' "$*" >&2; exit 1; }

while [ "$#" -gt 0 ]; do
    case "$1" in
        --no-build) BUILD=0 ;;
        --no-launch) LAUNCH=0 ;;
        --pair)
            [ "$#" -ge 2 ] || die "--pair needs IP:PORT"
            PAIR_ADDRESS="$2"
            shift ;;
        -h|--help) usage; exit 0 ;;
        -*) die "Unknown option: $1 (use --help)" ;;
        *)
            [ -z "$TV_ADDRESS" ] || die "Supply one TV address."
            TV_ADDRESS="$1" ;;
    esac
    shift
done

TV_ADDRESS="${TV_ADDRESS:-${LATVIANTUBE_TV:-}}"
[ -n "$TV_ADDRESS" ] || { usage >&2; die "Supply your TV's full IPv4 address, e.g. 192.168.50.91:5555"; }

validate_address() {
    local endpoint="$1" ip port octet normalized_ip=""
    [[ "$endpoint" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}(:[0-9]{1,5})?$ ]] \
        || die "Invalid TV address '$endpoint'. Replace xx with the actual number from your TV settings."
    ip="${endpoint%%:*}"
    port="${endpoint##*:}"
    [ "$port" != "$endpoint" ] || port=5555
    for octet in ${ip//./ }; do
        [ "$((10#$octet))" -le 255 ] || die "Invalid IPv4 address: $ip"
        normalized_ip="${normalized_ip}${normalized_ip:+.}$((10#$octet))"
    done
    [ "$((10#$port))" -ge 1 ] && [ "$((10#$port))" -le 65535 ] \
        || die "ADB port must be between 1 and 65535."
    printf '%s:%d\n' "$normalized_ip" "$((10#$port))"
}

TV_ADDRESS="$(validate_address "$TV_ADDRESS")"
if [ -n "$PAIR_ADDRESS" ]; then
    [[ "$PAIR_ADDRESS" == *:* ]] || die "--pair requires the TV's pairing port, IP:PORT."
    PAIR_ADDRESS="$(validate_address "$PAIR_ADDRESS")"
fi

# Preserve caller-supplied SDK/JDK settings; otherwise use the project's toolchain.
# ANDROID_USER_HOME also keeps the debug signing key consistent with run-tv.sh.
if [ -d "$TOOLCHAIN/android-sdk" ]; then
    export ANDROID_HOME="${ANDROID_HOME:-$TOOLCHAIN/android-sdk}"
    export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$TOOLCHAIN/android-user-home}"
fi
if [ "$BUILD" -eq 1 ]; then
    if [ -x "$TOOLCHAIN/jdk17/bin/java" ]; then
        export JAVA_HOME="${JAVA_HOME:-$TOOLCHAIN/jdk17}"
    fi
    if [ -d "$TOOLCHAIN/gradle-home" ]; then
        export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$TOOLCHAIN/gradle-home}"
    fi
fi

ADB="${LATVIANTUBE_ADB:-}"
if [ -z "$ADB" ]; then
    for sdk_dir in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$TOOLCHAIN/android-sdk" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
        if [ -n "$sdk_dir" ] && [ -x "$sdk_dir/platform-tools/adb" ]; then
            ADB="$sdk_dir/platform-tools/adb"
            break
        fi
    done
    [ -n "$ADB" ] || ADB="$(command -v adb || true)"
fi
[ -n "$ADB" ] && [ -x "$ADB" ] || die "adb not found. Install Android SDK Platform Tools or set LATVIANTUBE_ADB to its executable."
[[ "$SERVER_PORT" =~ ^[0-9]{1,5}$ ]] && [ "$((10#$SERVER_PORT))" -ge 1 ] \
    && [ "$((10#$SERVER_PORT))" -le 65535 ] || die "Invalid local ADB server port."
SERVER_PORT="$((10#$SERVER_PORT))"
adb_call() { "$ADB" -P "$SERVER_PORT" "$@"; }

if [ "$BUILD" -eq 1 ]; then
    printf 'Building LatvianTube…\n'
    (cd "$PROJECT_DIR" && ./gradlew assembleDebug --console=plain -q)
fi
[ -s "$APK" ] || die "APK missing: $APK. Run again without --no-build."

if [ -n "$PAIR_ADDRESS" ]; then
    printf 'Pairing with %s. Enter the pairing code displayed on your TV.\n' "$PAIR_ADDRESS"
    adb_call pair "$PAIR_ADDRESS" || die "Pairing failed. Check the pairing port/code on the TV."
fi

printf 'Connecting to %s… Accept the debugging prompt on your TV if shown.\n' "$TV_ADDRESS"
if ! CONNECT_OUTPUT="$(adb_call connect "$TV_ADDRESS" 2>&1)"; then
    printf '%s\n' "$CONNECT_OUTPUT" >&2
    die "Connection failed. Check network ADB debugging, the IP/port, and the shared network."
fi
printf '%s\n' "$CONNECT_OUTPUT"
case "$CONNECT_OUTPUT" in
    *"failed to connect"*|*"unable to connect"*|*"cannot connect"*)
        die "TV is unreachable. Check its IP/port, network ADB debugging, and the shared network." ;;
esac

# adb connect can exit 0 even when connection/authorization failed. Verify the exact device.
CONNECTED=0
for attempt in {1..30}; do
    if [ "$(adb_call -s "$TV_ADDRESS" get-state 2>/dev/null || true)" = device ]; then
        CONNECTED=1
        break
    fi
    sleep 1
done
[ "$CONNECTED" -eq 1 ] || die "TV is not authorized/connected. Enable network ADB, check its IP/port, and accept the TV prompt. For Wireless debugging, use --pair with the pairing port first."

printf 'Installing %s on %s…\n' "$APK" "$TV_ADDRESS"
if ! INSTALL_OUTPUT="$(adb_call -s "$TV_ADDRESS" install -r "$APK" 2>&1)"; then
    printf '%s\n' "$INSTALL_OUTPUT" >&2
    case "$INSTALL_OUTPUT" in
        *INSTALL_FAILED_UPDATE_INCOMPATIBLE*)
            die "The installed app has a different signing key. Build with its original key to update and retain saved videos/settings." ;;
        *) die "Installation failed; adb's error is shown above." ;;
    esac
fi
printf '%s\n' "$INSTALL_OUTPUT"

if [ "$LAUNCH" -eq 1 ]; then
    adb_call -s "$TV_ADDRESS" shell am force-stop lv.latviantube
    adb_call -s "$TV_ADDRESS" shell am start -W -n lv.latviantube/.ui.browse.BrowseActivity
fi
printf 'LatvianTube installed on %s.\n' "$TV_ADDRESS"
