# MangaBuffAuto V11.1 — Edge Back Gesture Fixed

Base: V11.1 Stability + Gestures.

## Fix
- Added `EdgeBackGestureLayout` around the existing WebView/SwipeRefreshLayout.
- A horizontal swipe beginning within 32dp of either physical screen edge and exceeding 72dp triggers WebView history back.
- The Activity is never finished by this gesture.
- Normal WebView vertical scrolling, taps, pull-to-refresh, and ordinary touch input are not intentionally intercepted.
- If WebView history is empty, the edge gesture is consumed and the app remains open.
- Existing system Back callback remains unchanged.

## Build note
The source was checked locally. Full Gradle build could not be executed in this environment because Gradle 9.6.0 was not available locally and the environment cannot reach `services.gradle.org`.
