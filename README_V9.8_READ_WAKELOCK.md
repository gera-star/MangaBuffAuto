# MangaBuff Auto V9.8 — ReadTask screen-off WakeLock experiment

## Base
V9.7 READ_WATCHDOG_RESTART.

## Purpose
This version tests whether the reader can continue advancing chapters while the phone screen is locked. The user observed that locking the phone shortly before the next chapter transition prevented the transition after about one minute.

## Changes
- Added `android.permission.WAKE_LOCK`.
- Added a temporary `PARTIAL_WAKE_LOCK` named `MangaBuffAuto:ReadTask`.
- The WakeLock is acquired only while the active task id is exactly `mangabuff_read`.
- The WakeLock remains held across the normal ReadTask retry, so a retry does not accidentally release it between attempts.
- The WakeLock is released when the ReadTask finishes, is stopped, is blocked/captcha-stopped, the runtime is destroyed, or the foreground service is destroyed/stopped.
- Added diagnostic log messages:
  - `POWER: READ_WAKELOCK_ACQUIRED`
  - `POWER: READ_WAKELOCK_RELEASED`

## Intentionally unchanged
- `ReadTask.kt` was not modified.
- No reader JS was modified.
- No CSS/DOM selectors were changed.
- No social-login code was changed.
- No 403 guard logic was changed.
- No profile logic was changed.
- No automatic CAPTCHA/403 bypass was added.

## Important
This is an experiment, not a claim that WebView background JavaScript is guaranteed to run identically with the screen off. A partial WakeLock keeps the CPU awake, but WebView/Chromium lifecycle restrictions can still affect background execution.

## Test
1. Start `Сам читать`.
2. Confirm a chapter is actively being read.
3. When the next chapter is expected in a few seconds, lock the phone.
4. Leave it locked for 1–5 minutes.
5. Unlock and check whether the chapter advanced.
6. Check the log for `POWER: READ_WAKELOCK_ACQUIRED` and later `POWER: READ_WAKELOCK_RELEASED`.

## Build note
The archive was statically validated (Kotlin brace balance, manifest permission, and unchanged `ReadTask.kt`). A full Gradle build cannot be performed in the current tool environment because Gradle distribution download is blocked by DNS/network restrictions.
