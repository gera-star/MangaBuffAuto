# Card Statistics Android Bridge V4

## What changed
- Replaced per-request `window.__mbCardStatsCb_*` callbacks with one persistent `window.__mbCardStatsNativeResult` dispatcher.
- Added request IDs and a bounded native request queue (4 concurrent requests).
- Added a 12-second JS timeout per request.
- Native bridge now returns HTTP status and error text for diagnostics instead of silently swallowing failures.
- Kept the existing `mbstat.space/cards?ids=...` API, 24h cache, and MangaBuff fallback logic.
- This change does not modify reader edge-swipe navigation, WebView navigation, automation runtime, or background keepalive.

## Why
The previous callback-per-request implementation could produce `window.__mbCardStatsCb_* is not a function` when a callback was removed/replaced before the asynchronous native response arrived. Android's WebView bridge is asynchronous, so a persistent message dispatcher is safer for this use case.

The implementation follows Android's documented guidance for JavaScript/native communication and keeps native work off the UI thread.
