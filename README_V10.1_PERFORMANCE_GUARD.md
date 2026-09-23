# MangaBuffAuto V10.1 — Performance Guard

## Changes

### Statistics
- Statistics panel is collapsed by default.
- Opening the panel performs one explicit DOM refresh.
- Automatic statistics polling every 4 seconds has been removed.
- Automatic local chapter counter has been removed.
- Statistics shown: diamonds, mine ore, cards, ads, comments.

### Background reader / locked screen
- Native background heartbeat is started only when the app is actually in background or the screen is off and `mangabuff_read` is active.
- Foreground no longer runs the native heartbeat.
- Background heartbeat interval: 500 ms.
- At 1x it scrolls 280 px/s; at 2x it scrolls exactly 560 px/s.
- Reader maintenance (`readerTick`) is executed natively about once per second while locked/backgrounded.
- The normal JS reader maintenance timer is stopped while backgrounded, so native and JS maintenance loops do not run simultaneously.
- When the app returns to the foreground, the normal JS reader maintenance timer is restored and the native heartbeat stops.
- Partial WakeLock remains active only during the read task.

### WebView / JS cleanup
- Tippy fix delayed timers are scheduled once per document and explicitly cleaned up on task stop.
- Reader maintenance timers are explicitly cleaned up on task finish.
- Existing next-chapter timer cleanup remains in the engine stop path.
- Stale run/document guards remain unchanged.

### UI performance
- Log UI buffer reduced to the last 300 entries.
- When the Logs panel is closed, the Compose log listener is detached, preventing every background log line from triggering UI state updates/recomposition.

## Locked-phone behavior
The code path is designed so that:

`SCREEN_OFF -> backgroundMode=true -> native heartbeat ON -> JS reader timer OFF -> WakeLock remains held`

and:

`SCREEN_ON + Activity foreground -> backgroundMode=false -> native heartbeat OFF -> JS reader timer restored`

A physical locked-phone test still has to be performed on the Redmi device because WebView renderer behavior is device/WebView-version dependent.

## Safety
No CAPTCHA bypass, stealth, fingerprint spoofing, IP rotation, proxy rotation, or block evasion was added.
