# Android E2E and Maestro Runbook

This guide exists to keep Android E2E work from turning into repeated CI archaeology. Use it before adding or debugging Maestro flows.

## What Runs in CI

The Android E2E workflow is `.github/workflows/android-e2e.yml`.

It is intentionally separate from normal PR CI because GitHub-hosted Android emulator jobs are slow and less stable than unit or integration tests. It runs nightly and by manual dispatch.

The workflow does the following:

1. Starts PostgreSQL and the disposable demo backend.
2. Installs mobile dependencies.
3. Runs `npx expo prebuild --platform android --no-install`.
4. Builds `mobile/android/app/build/outputs/apk/debug/app-debug.apk`.
5. Starts an Android emulator.
6. Starts Expo Metro inside the emulator step.
7. Installs the debug APK.
8. Runs the critical Maestro flows in `.maestro/`.

Expect the Android debug build step to take around 15 to 25 minutes on a cold hosted runner. Do not assume it is stuck until it is well outside that range or the job completes with logs.

## Manual Dispatch

```sh
gh workflow run android-e2e.yml --ref <branch>
gh run view <run-id> --json status,conclusion,jobs
gh run view <run-id> --log-failed
gh run download <run-id> --dir .artifacts/android-e2e-<run-id>
```

Useful artifact paths after download:

```text
.artifacts/android-e2e-<run-id>/android-e2e-diagnostics/backend-e2e.log
.artifacts/android-e2e-<run-id>/android-e2e-diagnostics/metro-e2e.log
.artifacts/android-e2e-<run-id>/android-e2e-diagnostics/.artifacts/android-e2e/logcat.txt
.artifacts/android-e2e-<run-id>/android-e2e-diagnostics/.artifacts/android-e2e/maestro-tests/<timestamp>/maestro.log
.artifacts/android-e2e-<run-id>/android-e2e-diagnostics/.artifacts/android-e2e/maestro-tests/<timestamp>/screenshot-*.png
```

Always inspect the screenshot and `maestro.log` before changing a selector. The screenshot is usually the fastest way to tell whether the element is missing, off-screen, or blocked by a dev overlay.

## Metro and App Launch Details

Do not start Metro in a previous workflow step. Start it inside `.github/scripts/android-e2e.sh`, which runs under `reactivecircus/android-emulator-runner`, so the process stays alive while the emulator and Maestro run.

Use Bash for the script:

```yaml
script: bash .github/scripts/android-e2e.sh
```

The script waits for:

```text
http://localhost:8081/status
```

Then it prewarms the native Expo entry bundle:

```text
http://localhost:8081/.expo/.virtual-metro-entry.bundle?platform=android&dev=true&lazy=true&minify=false&app=com.yuuka.mobile&modulesOnly=false&runModule=true&excludeSource=true&sourcePaths=url-server
```

Do not replace that with a generic `/index.bundle` URL or `node_modules/expo-router/entry.bundle`. The native debug app asks Expo for the virtual Metro entry; prewarming the wrong URL can leave the app on a blank screen while Maestro waits for `Yuuka`.

Required environment in the emulator step:

```yaml
CI: "1"
EXPO_UNSTABLE_HEADLESS: "1"
EXPO_PUBLIC_E2E: "1"
EXPO_PUBLIC_API_BASE_URL: http://localhost:8080/api/v1
```

`EXPO_UNSTABLE_HEADLESS=1` prevents Expo from launching React Native DevTools in GitHub Actions. Without it, Electron or Chrome sandbox startup can fail and Metro may never serve the app bundle.

`EXPO_PUBLIC_E2E=1` is compiled into the debug app for this workflow and tells the app to hide React Native LogBox overlays. This is for dev-build test hygiene only; it must not weaken product behavior or hide failed app assertions.

The script must keep these reverses before Maestro starts:

```sh
adb reverse tcp:8080 tcp:8080
adb reverse tcp:8081 tcp:8081
```

The app uses the reversed backend and Metro ports from inside the emulator.

## Emulator and Disk Pitfalls

The workflow frees disk before creating the emulator. Keep this cleanup unless runner images change materially:

```sh
rm -rf ~/.gradle/caches ~/.gradle/daemon ~/.gradle/native
rm -rf mobile/android/.gradle mobile/android/build
find mobile/android/app/build -mindepth 1 -maxdepth 1 ! -name outputs -exec rm -rf {} +
sudo rm -rf /usr/local/lib/android/sdk/ndk || true
```

This preserves the debug APK outputs while removing build caches that can push the runner over disk limits during Android system image installation.

Do not set `disable-animations: true` on the emulator runner. On this stack it enables the Android reduced-motion setting, which makes Reanimated emit a development warning. That warning opens LogBox and can cover controls at the bottom of the screen.

## Writing Reliable Maestro Flows

Use the existing sign-in flow:

```yaml
- runFlow: shared/sign-in.yaml
```

Use deterministic disposable names with an `E2E` prefix. Never run these flows against production data.

Prefer user-visible labels and accessibility labels over coordinates. If a control may be below the fold, scroll to it before tapping:

```yaml
- scrollUntilVisible:
    centerElement: true
    element: "Save entry"
    direction: DOWN
- tapOn: "Save entry"
```

After saving a form, assert a detail-screen control before continuing. Text from the form can remain visible briefly or be matched in an unexpected place.

Good:

```yaml
- tapOn: "Save entry"
- extendedWaitUntil:
    visible: "Food"
    timeout: 10000
- extendedWaitUntil:
    visible: "Add entry"
    timeout: 10000
```

Risky:

```yaml
- tapOn: "Save entry"
- assertVisible: "Food"
```

If a row action is below the visible portion of the list, scroll to the action itself:

```yaml
- scrollUntilVisible:
    centerElement: true
    element: "Move Food up"
    direction: DOWN
- tapOn: "Move Food up"
```

This is not a weakened assertion. It models the user action needed to reach an off-screen control.

After typing into a field inside a bottom sheet, assume the Android keyboard may cover the next field or action. Hide the keyboard and scroll to the next target before tapping it:

```yaml
- inputText: "2026-07-16T12:00:00Z"
- hideKeyboard
- scrollUntilVisible:
    centerElement: true
    element: "Note (optional)"
    direction: DOWN
- tapOn: "Note (optional)"
```

Use the same pattern before bottom-sheet save buttons when the sheet content can extend below the fold.

When replacing an existing text-field value, remember that Maestro `eraseText` sends backspaces from the current cursor position. Tap near the end of the field before erasing, or a suffix can remain and make the next value invalid:

```yaml
- tapOn:
    text: "Effective date and time"
    point: "90%,50%"
- eraseText: 100
- inputText: "2026-07-16T12:00:00Z"
```

For full-screen React Native modals, prefer `back` when the next assertion only needs the modal dismissed. In CI, tapping a small close icon can be reported as completed even when the screenshot still shows the modal. Follow the back action with a wait for the underlying screen:

```yaml
- back
- extendedWaitUntil:
    visible: "Close paycheck"
    timeout: 10000
```

## Debugging Failures Fast

Use this order:

1. Check `gh run view <run-id> --log-failed` for the failed command.
2. Download artifacts.
3. Open the failed screenshot.
4. Read the matching `maestro.log` around the failed selector.
5. Check `metro-e2e.log` for bundle or LogBox warnings.
6. Check `logcat.txt` only when app startup, native crashes, networking, or blank screens are involved.

Common symptoms:

- Blank screen before `Yuuka`: Metro is not serving the native Expo virtual entry, Metro died, or adb reverse is missing.
- `curl localhost:8081/status` fails at first: normal while Metro starts. The script retries.
- `Quickstep isn't responding`: emulator system UI noise, not an app failure. Keep the launcher force-stop in `.github/scripts/android-e2e.sh`, the optional `Close app` tap in `shared/sign-in.yaml`, and the short settle after APK install.
- `Save entry` not found: usually off-screen. Use `scrollUntilVisible`.
- Next action after save still sees form text: the save tap may have been intercepted or navigation has not completed. Wait for a detail-screen control such as `Add entry`.
- `Open debugger to view warnings`: a dev warning opened LogBox. Keep `EXPO_PUBLIC_E2E=1`; if a new app warning appears, fix the warning if it is in app code, or document the dependency warning if it is external.
- `No space left on device` while installing system images: preserve the disk cleanup step.

## Before Pushing

For Maestro-only edits:

```sh
git diff --check -- .maestro .github/workflows/android-e2e.yml .github/scripts/android-e2e.sh
mobile/node_modules/.bin/prettier.cmd --check .maestro/*.yaml .github/workflows/android-e2e.yml
```

For app-code changes that affect E2E behavior, also run the relevant non-E2E checks. At minimum:

```sh
cd mobile
npm run typecheck
npm test -- --runTestsByPath src/__tests__/app-root.test.tsx
```

Run broader tests when the app-code change touches shared behavior, routing, forms, API contracts, or business logic.

## What Not To Do

- Do not skip a Maestro flow to make the workflow green.
- Do not weaken selectors into broad matches that could pass on the wrong screen.
- Do not remove backend, Metro, logcat, or Maestro artifact uploads.
- Do not use production data or production credentials.
- Do not assume a long Android build is hung before checking recent run timings.
- Do not change the native bundle prewarm URL without proving the debug app asks Metro for a different entry.
