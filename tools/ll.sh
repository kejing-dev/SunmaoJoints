#!/usr/bin/env bash
# Sunmao Joints dev harness (Windows Git Bash). Pinned to one emulator: DEV (the factory's emu_borrow.py hands it out).
#   tools/ll.sh cmd NAME [ARG]     debug broadcast:  am broadcast -a io.embodit.sunmaojoints.DEBUG --es cmd NAME --es a ARG
#   tools/ll.sh shot OUT.png       still capture (pico-cli)
#   tools/ll.sh log [REGEX]        logcat lines of tag SunmaoJoints (optionally filtered)
#   tools/ll.sh install            install the debug APK and start the app
set -u
DEV="${DEV:-emulator-5556}"
PKG=io.embodit.sunmaojoints
ADB="${ADB:-adb}"
HERE="$(cd "$(dirname "$0")" && pwd)"
case "${1:-}" in
  cmd) "$ADB" -s "$DEV" shell am broadcast -a $PKG.DEBUG -p $PKG --es cmd "$2" --es a "\"${3:-}\"" >/dev/null ;;
  shot) pico-cli capture screenshot --device "$DEV" --out "$2" --replace >/dev/null ;;
  log) "$ADB" -s "$DEV" logcat -d -s SunmaoJoints:V BoardInput:V | grep -E "${2:-.}" ;;
  install) "$ADB" -s "$DEV" install -r -d "$HERE/../app/build/outputs/apk/debug/app-debug.apk" && "$ADB" -s "$DEV" shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 ;;
  *) sed -n 2,7p "$0" ;;
esac
