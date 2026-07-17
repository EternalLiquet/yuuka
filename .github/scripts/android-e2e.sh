#!/usr/bin/env bash
set -euo pipefail

flow="${1:-}"
apk="${2:-artifacts/android-e2e-apk/app-e2e.apk}"

case "$flow" in
  scratch)
    flow=".maestro/01-scratch-lifecycle.yaml"
    ;;
  paybacks)
    flow=".maestro/02-payback-delete-reassign.yaml"
    ;;
  templates)
    flow=".maestro/03-template-application-draft.yaml"
    ;;
  recurring)
    flow=".maestro/04-recurring-bill-import.yaml"
    ;;
esac

if [ -z "$flow" ]; then
  echo "Usage: $0 <flow-id-or-path> [apk-path]" >&2
  exit 64
fi

if [ ! -f "$flow" ]; then
  echo "Maestro flow not found: $flow" >&2
  exit 66
fi

if [ ! -f "$apk" ]; then
  echo "APK not found: $apk" >&2
  exit 66
fi

flow_id="$(basename "$flow" .yaml)"
flow_id="${flow_id#??-}"
diagnostics_dir="artifacts/android-e2e/$flow_id"
mkdir -p "$diagnostics_dir"

collect_diagnostics() {
  adb logcat -d > "$diagnostics_dir/logcat.txt" || true
  adb devices -l > "$diagnostics_dir/adb-devices.txt" || true
  adb shell dumpsys activity activities > "$diagnostics_dir/activity.txt" || true
  adb shell dumpsys window windows > "$diagnostics_dir/window.txt" || true
  cp -R "$HOME/.maestro/tests" "$diagnostics_dir/maestro-tests" || true
}
trap collect_diagnostics EXIT

adb logcat -c
adb reverse tcp:8080 tcp:8080
adb install -r "$apk"
if adb shell pm path com.android.inputmethod.latin >/dev/null 2>&1; then
  adb shell pm grant com.android.inputmethod.latin android.permission.READ_CONTACTS || true
fi
adb shell am force-stop com.android.launcher3 || true
sleep 5

set +e
maestro test \
  -e YUUKA_EMAIL=e2e@yuuka.local \
  -e YUUKA_PASSWORD=E2ePassword123 \
  "$flow" \
  2>&1 | tee "$diagnostics_dir/maestro-output.txt"
status=${PIPESTATUS[0]}
set -e

exit "$status"
