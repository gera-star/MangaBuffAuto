# V11.1 Back Gesture Fix

## Fix
- Replaced the fragile direct `OnBackInvokedCallback` implementation with the lifecycle-aware AndroidX `OnBackPressedCallback`.
- The AndroidX callback receives the normal Android Back action, including the system edge/predictive Back gesture on supported gesture-navigation devices.
- When WebView has history, Back calls `WebView.goBack()` and stays inside the app.
- When WebView has no history, the callback consumes the Back action and does NOT call `finish()` or exit the Activity.
- Removed the duplicate `android.os.Build` import that caused `compileDebugKotlin` to fail with `Conflicting import: imported name Build is ambiguous`.
- No changes were made to automation, Scheduler, recovery, Reader, Foreground Service, profiles or task logic.

## Scope
Only Back/predictive edge gesture handling was changed. Existing automation, WebView recovery, Scheduler, Reader, Foreground Service, profiles and task logic were not intentionally changed.

## Build
The source was checked after the fix. A local `./gradlew assembleDebug --offline` attempt could not start because this environment does not have the Gradle 9.6.0 distribution cached and cannot reach `services.gradle.org`. The previous compile error itself was removed: the duplicate `android.os.Build` import is gone.
