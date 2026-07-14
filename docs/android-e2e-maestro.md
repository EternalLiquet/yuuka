# Android E2E and Maestro Runbook

This guide exists to keep Android E2E work from turning into repeated CI archaeology. Use it before adding or debugging Maestro flows.

## What Runs in CI

The Android E2E workflow is `.github/workflows/android-e2e.yml`.

It is intentionally separate from normal PR CI because GitHub-hosted Android emulator jobs are slow and less stable than unit or integration tests. It runs nightly and by manual dispatch.

The workflow has two stages:

1. `build-e2e-apk` installs mobile dependencies and builds one bundled Android APK.
2. `run-e2e-flow` downloads that APK and runs each selected Maestro flow in its own isolated job.

The E2E APK is built with Expo/React Native's generated `release` Android variant. The generated Gradle config signs release APKs with the debug keystore in this project, and the workflow adds a CI-only release manifest so the app can reach the disposable localhost HTTP backend. The APK embeds the JavaScript bundle and must launch without Metro.

The build job validates an executable JavaScript bundle entry with exact archive-entry matching. It accepts `assets/index.android.bundle` or a Hermes bytecode asset such as `assets/<name>.hbc`; `.meta` files do not count as proof that the APK can launch without Metro.

When all flows are selected, the workflow shape is:

```text
build-e2e-apk
|-- scratch-lifecycle
|-- payback-delete-reassign
`-- template-application
```

The flow jobs use `fail-fast: false`, so one Maestro failure does not cancel the other flows.

Expect the Android APK build step to take around 15 to 25 minutes on a cold hosted runner. Do not assume it is stuck until it is well outside that range or the job completes with logs.

## Manual Dispatch

Manual runs can select one flow or all flows:

```sh
gh workflow run android-e2e.yml --ref <branch> -f flow=scratch
gh workflow run android-e2e.yml --ref <branch> -f flow=paybacks
gh workflow run android-e2e.yml --ref <branch> -f flow=templates
gh workflow run android-e2e.yml --ref <branch> -f flow=all
```

Nightly runs execute all flows. Manual runs with a single flow build the shared APK once and run only that selected flow.

Useful inspection commands:

```sh
gh run view <run-id> --json status,conclusion,jobs
gh run view <run-id> --log-failed
gh run download <run-id> --dir artifacts/android-e2e-<run-id>
```

`gh run view <run-id> --job <job-id> --log` may not return step logs while the job is still in progress. If a run is still active, use the JSON job/step status to see where it is. Pull the failed command log and artifacts after the job completes.

Useful artifact paths after download:

```text
artifacts/android-e2e-<run-id>/android-e2e-scratch-diagnostics/backend-e2e-scratch.log
artifacts/android-e2e-<run-id>/android-e2e-scratch-diagnostics/artifacts/android-e2e/scratch-lifecycle/logcat.txt
artifacts/android-e2e-<run-id>/android-e2e-scratch-diagnostics/artifacts/android-e2e/scratch-lifecycle/maestro-output.txt
artifacts/android-e2e-<run-id>/android-e2e-scratch-diagnostics/artifacts/android-e2e/scratch-lifecycle/maestro-tests/<timestamp>/maestro.log
artifacts/android-e2e-<run-id>/android-e2e-scratch-diagnostics/artifacts/android-e2e/scratch-lifecycle/maestro-tests/<timestamp>/screenshot-*.png
```

Payback and template diagnostics use the same structure under `android-e2e-paybacks-diagnostics` and `android-e2e-templates-diagnostics`.

Always inspect the screenshot and `maestro.log` before changing a selector. The screenshot is usually the fastest way to tell whether the element is missing, off-screen, or blocked by an app overlay.

When downloading artifacts into the repository, use a temporary ignored directory such as `artifacts/android-e2e-<run-id>/`, inspect it, and remove it before committing. Diagnostics commonly include screenshots, logs, and device state that should not become source files.

## App Launch Details

Flow jobs do not start Expo or Metro. The runner script only installs the downloaded APK, reverses the backend port, and runs the requested Maestro file:

```sh
adb reverse tcp:8080 tcp:8080
bash .github/scripts/android-e2e.sh scratch artifacts/android-e2e-apk/app-e2e.apk
```

Do not add `adb reverse tcp:8081 tcp:8081`, Metro status polling, or bundle prewarming back to the flow jobs. If the APK hangs on startup, verify the `build-e2e-apk` job built `assembleRelease` and that the APK contains `assets/index.android.bundle` or an `assets/*.hbc` bundle entry, not only a `.meta` file.

`EXPO_PUBLIC_E2E=1` is compiled into the E2E APK and tells the app to hide React Native LogBox overlays. This is for test hygiene only; it must not hide failed app assertions.

Because the E2E APK is a release build, the app keeps the normal release requirement that API URLs use HTTPS. The E2E flag adds one narrow exception for the disposable CI backend: release builds with `EXPO_PUBLIC_E2E=1` may use HTTP only for explicit loopback hosts, `localhost` or `127.0.0.1`. Arbitrary production HTTP endpoints remain rejected, including in E2E mode.

## Emulator and Disk Pitfalls

The flow jobs free only generated Android build output and the hosted runner NDK before creating the emulator:

```sh
rm -rf mobile/android/.gradle mobile/android/build mobile/android/app/build || true
sudo rm -rf /usr/local/lib/android/sdk/ndk || true
```

Do not delete broad Gradle caches unless disk pressure proves it is necessary. The backend still benefits from Gradle caching in each flow job.

Do not set `disable-animations: true` on the emulator runner. On this stack it enables the Android reduced-motion setting, which makes Reanimated emit a development warning. That warning can cover controls at the bottom of the screen.

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

After saving an entry on a detail screen with filter controls, the saved row may be below the current viewport even though the save succeeded. First wait for a stable detail action such as `Add entry`, then scroll to the row action you intend to use:

```yaml
- tapOn: "Save entry"
- extendedWaitUntil:
    visible: "Add entry"
    timeout: 10000
- scrollUntilVisible:
    centerElement: true
    element: "Move Food up"
    direction: DOWN
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

After scrolling to row actions, the row summary text may be above the viewport even though the action controls are visible. If the state you need to prove is ordering, assert visible order-specific controls rather than an off-screen summary label:

```yaml
- tapOn: "Move E2E Food up"
- assertVisible: "Move E2E Food down"
- assertVisible: "Move E2E Rent Adjusted up"
```

Consecutive detail-list summary assertions can have the opposite problem: the first row is visible, but the second row summary is just below the Android viewport. Scroll to the second summary before asserting it, especially after a detail screen with filters or action buttons above the list:

```yaml
- assertVisible: 'Entry 1: E2E Food, \$70\.00, Spending Bucket'
- scrollUntilVisible:
    centerElement: true
    element: 'Entry 2: E2E Rent Adjusted, \$80\.00, Bill, Manual Pay'
    direction: DOWN
- assertVisible: 'Entry 2: E2E Rent Adjusted, \$80\.00, Bill, Manual Pay'
```

The same rule applies to full-screen form modals after typing into fields near the top. The Android viewport can show the field label while the actual accessible control is still below the navigation bar or below the fold. A failure like this:

```text
Element not found: Text matching regex: Apply to Payback, selected No Payback
```

can still mean the selector exists. Check the failed screenshot. If the label is visible but the selector body is cut off at the bottom, scroll to the selector before tapping:

```yaml
- hideKeyboard
- scrollUntilVisible:
    centerElement: true
    element: "Apply to Payback, selected No Payback"
    direction: DOWN
- tapOn: "Apply to Payback, selected No Payback"
```

Create-paycheck screens can have the same issue after entering an amount. Allocation status text is below the core paycheck fields, so scroll to the status before asserting exact, under-, or over-allocation:

```yaml
- hideKeyboard
- scrollUntilVisible:
    centerElement: true
    element: '\$50\.00 over-allocated\.'
    direction: DOWN
- extendedWaitUntil:
    visible: '\$50\.00 over-allocated\.'
    timeout: 10000
```

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

Do not rely on a full-screen modal title as the only readiness assertion when it sits under the Android status bar. The title can appear in the screenshot but fail a Maestro visibility assertion if it is clipped at the top edge. Wait for a stable in-form field or action instead:

```yaml
- tapOn: "Edit E2E Rent"
- extendedWaitUntil:
    visible: "Due offset days (optional)"
    timeout: 10000
```

When a flow creates nested detail screens and then switches tabs, first navigate back to a stable list landmark. Generic labels such as `Active` can also appear as status text in paychecks or paybacks, so use the tab-specific accessibility labels instead of the raw tab title:

```yaml
- back
- back
- extendedWaitUntil:
    visible: "New paycheck"
    timeout: 10000
- tapOn: "Paybacks tab"
```

The app's detail routes hide the bottom tab bar. After saving inside a paycheck or payback detail screen, use the visible app `Back` control when present, then wait for the tab-screen landmark before tapping another tab:

```yaml
- tapOn: "Back"
- extendedWaitUntil:
    visible: "New paycheck"
    timeout: 10000
- tapOn: "Paybacks tab"
```

The bottom tabs currently expose `Active tab`, `History tab`, `Paybacks tab`, `Templates tab`, and `Settings tab`. Prefer those labels in Maestro. A raw `tapOn: "Active"` can match an `Active` status badge inside a payback card and open the wrong detail screen.

## Debugging Failures Fast

Use this order:

1. Check `gh run view <run-id> --log-failed` for the failed command.
2. Download artifacts.
3. Open the failed screenshot.
4. Read the matching `maestro.log` around the failed selector.
5. Check `logcat.txt` when app startup, native crashes, networking, or blank screens are involved.
6. Check `backend-e2e-<flow>.log` for API readiness or data setup failures.

Common symptoms:

- Blank screen before `Yuuka`: the E2E APK may not contain the JavaScript bundle, the app crashed, or `adb reverse tcp:8080 tcp:8080` is missing.
- `Quickstep isn't responding`: emulator system UI noise, not an app failure. Keep the launcher force-stop in `.github/scripts/android-e2e.sh`, the optional `Close app` tap in `shared/sign-in.yaml`, and the short settle after APK install.
- `Save entry` not found: usually off-screen. Use `scrollUntilVisible`.
- A selector label is visible but its `tapOn` target is not found: the accessible Pressable may be lower than the text label and partly below the fold. Scroll to the selector's accessibility label, not just the visible section heading.
- Next action after save still sees form text: the save tap may have been intercepted or navigation has not completed. Wait for a detail-screen control such as `Add entry`.
- `Open debugger to view warnings`: a warning opened LogBox. Keep `EXPO_PUBLIC_E2E=1`; if a new app warning appears, fix the warning if it is in app code, or document the dependency warning if it is external.
- `No space left on device` while installing system images: keep cleanup targeted first, then only broaden it with evidence from `df -h`.

## Before Pushing

For workflow or Maestro-only edits:

```sh
git diff --check -- .maestro .github/workflows/android-e2e.yml .github/scripts/android-e2e.sh docs/android-e2e-maestro.md
mobile/node_modules/.bin/prettier.cmd --check .maestro/*.yaml .github/workflows/android-e2e.yml
bash -n .github/scripts/android-e2e.sh
```

For Android build changes, also run:

```sh
cd mobile
npm run typecheck
cd android
./gradlew assembleRelease --no-daemon -PreactNativeArchitectures=x86_64
apk="app/build/outputs/apk/release/app-release.apk"
unzip -Z1 "$apk" | grep -Eq '^(assets/index\.android\.bundle|assets/.+\.hbc)$'
```

Run broader tests when the app-code change touches shared behavior, routing, forms, API contracts, or business logic.

Do not repeatedly rerun the entire E2E suite while debugging. Run the smallest affected job or flow. Limit automatic fix-and-rerun attempts to two, and if GitHub Actions E2E validation still fails after two suite runs, stop and revisit the next day with the failing step, logs, screenshots, and likely root cause.

## What Not To Do

- Do not skip a Maestro flow to make the workflow green.
- Do not weaken selectors into broad matches that could pass on the wrong screen.
- Do not add Metro startup, Metro port reversal, or bundle prewarming to flow jobs.
- Do not remove backend, logcat, Maestro, or flow-specific artifact uploads.
- Do not use production data or production credentials.
- Do not assume a long Android build is hung before checking recent run timings.
