#!/usr/bin/env bash
#
# install-watch.sh — build + install the debug APK onto the watch via adb
# (USB or Wi-Fi ADB).
#
# Usage:
#   scripts/install-watch.sh                # build + install to connected device
#   scripts/install-watch.sh --ip IP        # Wi-Fi ADB connect first (e.g. --ip 192.168.1.50)
#   scripts/install-watch.sh --skip-build   # reuse existing app-debug.apk
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
SKIP_BUILD=0; WATCH_IP=""
say(){ printf '[watch] %s\n' "$*"; }
die(){ printf '[watch] ERROR: %s\n' "$*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-build) SKIP_BUILD=1 ;;
    --ip) shift; WATCH_IP="${1%%:*}" ;;
    -h|--help) sed -n '3,9p' "$0"; exit 0 ;;
    *) die "unknown arg: $1" ;;
  esac; shift
done

ADB_BIN="$(command -v adb || true)"
[[ -z "$ADB_BIN" && -x "$HOME/Library/Android/sdk/platform-tools/adb" ]] && \
  ADB_BIN="$HOME/Library/Android/sdk/platform-tools/adb"
[[ -z "$ADB_BIN" ]] && die "adb not found in PATH"

if [[ $SKIP_BUILD -eq 0 ]]; then
  if [[ "$(uname)" == "Darwin" ]]; then
    J17="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
    [[ -n "$J17" ]] || J17="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
    [[ -d "$J17" ]] || die "JDK 17 required for AGP (brew install openjdk@17)"
    export JAVA_HOME="$J17"
  fi
  say "building debug APK..."
  ( cd "$ROOT" && ./gradlew :app:assembleDebug --console=plain ) || die "gradle build failed"
fi
[[ -f "$APK" ]] || die "APK not found: $APK (run without --skip-build)"
say "APK: $(du -h "$APK" | cut -f1)"

AC() { "$ADB_BIN" devices 2>/dev/null | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ' || echo 0; }

if [[ "$(AC)" == "0" ]]; then
  if [[ -n "$WATCH_IP" ]]; then
    say "connecting Wi-Fi ADB to ${WATCH_IP}:5555 ..."
    "$ADB_BIN" connect "${WATCH_IP}:5555" || true; sleep 1
  fi
  [[ "$(AC)" != "0" ]] || die "no adb device found — connect the watch or pass --ip <addr>"
fi

say "installing via adb ..."
"$ADB_BIN" install -r "$APK" || die "adb install failed (confirm any prompt on the watch screen)"

say "DONE - installed com.dji360.watch"
say "Launch 'OSMO 360 Remote', grant PERMS, press SCAN."
say "Logs: adb shell logcat | grep DJIRemote"
