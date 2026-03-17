#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
EMULATOR_BIN="${EMULATOR_BIN:-$HOME/Android/Sdk/emulator/emulator}"
AVD_NAME="${AVD_NAME:-Medium_Phone_API_36.1}"
PACKAGE_NAME="${PACKAGE_NAME:-com.example.xgglassapp}"

usage() {
  cat <<'EOF'
Usage:
  scripts/debug-app.sh [command]

Commands:
  full          Build, install, run app, then tail logcat (default)
  run           Launch app only
  install       Install APK only
  build         Build debug APK only
  clean         Uninstall app from simulator/device only
  logs          Tail logcat for app process only
  crash         Show crash-focused logcat
  clean-install Uninstall app, install again, then run

Optional environment variables:
  ADB          Path to adb binary
  EMULATOR_BIN Path to emulator binary
  AVD_NAME     Android Virtual Device name
  PACKAGE_NAME App package (default: com.example.xgglassapp)
EOF
}

require_tool() {
  local tool_path="$1"
  local tool_name="$2"
  if [[ ! -x "$tool_path" ]]; then
    echo "Error: $tool_name not found or not executable at: $tool_path" >&2
    exit 1
  fi
}

has_connected_device() {
  "$ADB" devices | awk 'NR>1 && $2=="device" {found=1} END {exit !found}'
}

start_emulator_if_needed() {
  if has_connected_device; then
    return
  fi

  echo "No connected device. Starting emulator: $AVD_NAME"
  nohup "$EMULATOR_BIN" -avd "$AVD_NAME" >/tmp/xg-emulator.log 2>&1 &

  "$ADB" wait-for-device
  until [[ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
    sleep 2
  done
  echo "Emulator is ready."
}

build_apk() {
  echo "Building debug APK..."
  (cd "$ROOT_DIR" && ./gradlew :app:assembleDebug)
}

clean_only() {
  echo "Uninstalling app (clean only)..."
  "$ADB" uninstall "$PACKAGE_NAME" >/dev/null 2>&1 || true
  echo "Uninstall complete."
}

install_apk() {
  echo "Installing app..."
  local output
  set +e
  output="$(cd "$ROOT_DIR" && xg-glass install 2>&1)"
  local status=$?
  set -e

  if [[ $status -eq 0 ]]; then
    echo "$output"
    return
  fi

  echo "$output"
  if echo "$output" | grep -q "INSTALL_FAILED_INSUFFICIENT_STORAGE"; then
    echo "Install failed due to insufficient storage. Trying uninstall + reinstall..."
    "$ADB" uninstall "$PACKAGE_NAME" >/dev/null 2>&1 || true
    (cd "$ROOT_DIR" && xg-glass install)
    return
  fi

  return $status
}

run_app() {
  echo "Launching app..."
  (cd "$ROOT_DIR" && xg-glass run)
}

tail_logs() {
  local pid
  pid="$("$ADB" shell pidof -s "$PACKAGE_NAME" 2>/dev/null | tr -d '\r' || true)"
  if [[ -z "$pid" ]]; then
    echo "App process not found. Starting app first..."
    run_app
    sleep 1
    pid="$("$ADB" shell pidof -s "$PACKAGE_NAME" 2>/dev/null | tr -d '\r' || true)"
  fi

  if [[ -z "$pid" ]]; then
    echo "Could not resolve PID for $PACKAGE_NAME" >&2
    exit 1
  fi

  echo "Tailing logcat for PID $pid (Ctrl+C to stop)..."
  "$ADB" logcat --pid="$pid" -v time
}

crash_logs() {
  echo "Showing crash-focused logs (Ctrl+C to stop)..."
  "$ADB" logcat -v time AndroidRuntime:E System.err:W '*:S'
}

clean_install() {
  echo "Uninstalling app (ignore failure if not installed)..."
  "$ADB" uninstall "$PACKAGE_NAME" >/dev/null 2>&1 || true
  install_apk
  run_app
}

main() {
  require_tool "$ADB" "adb"
  require_tool "$EMULATOR_BIN" "emulator"

  local cmd="${1:-full}"
  case "$cmd" in
    -h|--help|help)
      usage
      ;;
    full)
      start_emulator_if_needed
      build_apk
      install_apk
      run_app
      tail_logs
      ;;
    build)
      build_apk
      ;;
    clean)
      start_emulator_if_needed
      clean_only
      ;;
    install)
      start_emulator_if_needed
      install_apk
      ;;
    run)
      start_emulator_if_needed
      run_app
      ;;
    logs)
      start_emulator_if_needed
      tail_logs
      ;;
    crash)
      start_emulator_if_needed
      crash_logs
      ;;
    clean-install)
      start_emulator_if_needed
      clean_install
      ;;
    *)
      echo "Unknown command: $cmd" >&2
      usage
      exit 1
      ;;
  esac
}

main "$@"
