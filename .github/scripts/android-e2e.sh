#!/usr/bin/env bash
set -euo pipefail

mkdir -p .artifacts/android-e2e

cd mobile
nohup ./node_modules/.bin/expo start --localhost > ../metro-e2e.log 2>&1 &
metro_pid=$!
cd ..

cleanup() {
  kill "$metro_pid" || true
}
trap cleanup EXIT

metro_ready=0
for attempt in {1..120}; do
  if curl --fail --silent --show-error --connect-timeout 2 --max-time 30 \
    "http://localhost:8081/status" | grep -q "packager-status:running"; then
    metro_ready=1
    break
  fi
  sleep 2
done

if [ "$metro_ready" -ne 1 ]; then
  cat metro-e2e.log
  exit 1
fi

curl --fail --silent --show-error --connect-timeout 2 --max-time 180 \
  "http://localhost:8081/.expo/.virtual-metro-entry.bundle?platform=android&dev=true&lazy=true&minify=false&app=com.yuuka.mobile&modulesOnly=false&runModule=true&excludeSource=true&sourcePaths=url-server" \
  > /dev/null

adb logcat -c
adb reverse tcp:8080 tcp:8080
adb reverse tcp:8081 tcp:8081
adb install -r mobile/android/app/build/outputs/apk/debug/app-debug.apk
sleep 5

set +e
maestro test -e YUUKA_EMAIL=e2e@yuuka.local -e YUUKA_PASSWORD=E2ePassword123 .maestro/01-scratch-lifecycle.yaml
first_status=$?
if [ "$first_status" -eq 0 ]; then
  maestro test -e YUUKA_EMAIL=e2e@yuuka.local -e YUUKA_PASSWORD=E2ePassword123 .maestro/02-payback-delete-reassign.yaml
  second_status=$?
else
  second_status=0
fi
set -e

adb logcat -d > .artifacts/android-e2e/logcat.txt || true
cp -R "$HOME/.maestro/tests" .artifacts/android-e2e/maestro-tests || true

if [ "$first_status" -ne 0 ]; then
  exit "$first_status"
fi

exit "$second_status"
