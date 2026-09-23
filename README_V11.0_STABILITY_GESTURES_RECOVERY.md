# MangaBuffAuto V11.0 — Stability / Recovery / Gestures

## Included

- Durable checkpoint state separate from Activity/WebView lifetime.
- Active profile persists through `MultiProfileManager`.
- Last internal MangaBuff URL is restored after process recreation. External OAuth URLs are never restored automatically.
- Auto mode and scheduler timestamps persist. Scheduler is recreated and restarted when the saved Auto Mode is enabled.
- Active task/run is checkpointed only for diagnostics. An interrupted old execution is NEVER resumed; it is invalidated and a fresh scheduler decision is used.
- Reading speed persists per profile.
- WebView renderer crash creates a fresh WebView attached to the same AndroidX WebView Profile, restores the last safe internal URL, and increments the UI WebView version so the Activity replaces the dead renderer.
- Android predictive-back compatible dispatcher handling: Back goes through WebView history when available, otherwise falls back to the system. Runtime back callbacks are detached when Activity leaves the foreground.
- Pull-to-refresh behaves like a browser: swipe down at the top of the page to reload. It is disabled logically while an automation task is active so a user gesture cannot interrupt a running task.
- Removed the bottom-bar refresh button completely.
- Multi-profile cookies/storage remain inside WebView Profiles; the app does not persist passwords/cookies/tokens.
- One active WebView task at a time.
- CAPTCHA/Cloudflare/403 safety stop remains in place. No bypass or evasion logic.

## Version

`versionCode = 11`
`versionName = 0.11-stability`

## Important validation

ZIP integrity was checked with `unzip -tq`. Full Gradle compilation cannot be executed in the preparation environment because Gradle 9.6.0 must be downloaded from `services.gradle.org`, which is unavailable here.

Before installing, open this project in Android Studio and run `Build > Make Project`.
