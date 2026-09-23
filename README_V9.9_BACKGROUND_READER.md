# MangaBuffAuto V9.9 — Background Reader heartbeat

Based on V9.8 READ_WAKELOCK.

## What changed

- Keeps the V9.8 temporary `PARTIAL_WAKE_LOCK`.
- Adds a native Android heartbeat while `mangabuff_read` is active.
- The heartbeat runs every 250 ms and invokes the existing reader safety/flow logic through `window.__mangaBuffNativeReadTick()`.
- When the WebView is backgrounded and `requestAnimationFrame` is throttled, the heartbeat performs a small direct `window.scrollBy()` step so the reader is not dependent only on page animation frames.
- The heartbeat is stopped on task finish, retry transition, manual stop, WebView detach, and engine destroy.
- No CAPTCHA bypass, fingerprint spoofing, proxy/IP rotation, or anti-detection logic was added.
- Existing ReadTask selectors and chapter navigation logic are otherwise preserved.

## Important

This is an experimental background-reading fix. Android/WebView may still impose renderer lifecycle limitations; the app does not attempt to bypass those platform restrictions.
