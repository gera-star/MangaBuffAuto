# MangaBuffAutoBackground — stability audit (2026-10-15)

## Scope

This is a code-level review of the nested background application on branch `background-rebuild`. The repository also contains a separate root application; this audit and the changes listed below target only `MangaBuffAutoBackground/`.

This is not a claim that the complete Android app has been built or device-tested. Android SDK/Gradle are not available in the audit environment.

## Changes made

1. **One active automation account at a time.** The foreground service uses `AccountExecutionQueue`, a small class that owns the semaphore and identity-checked queued-job markers. Waiting accounts are tracked separately so the WebView watchdog does not mistake a healthy queued job for a broken one. Unit tests were added for serialization, cancellation while waiting, and stale queued markers.
2. **Bounded log history.** The UI retains the newest 2,000 log entries in memory, newest first. Older entries roll off instead of clearing all recent diagnostics at once. Service-broadcast log entries now keep their originating component and timestamp.
3. **A log-buffer unit test.** It checks the 2,000-entry bound, newest/oldest retention, and ordering.
4. **Last-manga completion fallback.** A confirmed terminal chapter tries to mark the manga in the site's “Прочитано” folder before the normal catalog flow. Mark-read UI failures/timeouts are retried up to three times; if the site never confirms the folder update, the reader records that failure and continues rather than hanging forever or discarding the confirmed chapter.
5. **Watchdog recovery limits and single-flight mark-read.** The current branch also includes identity-checked cancellation of jobs stuck without a WebView, a three-recovery budget per ten-minute window, heartbeat exception isolation, and a gate preventing the mark-read callback and timeout from scheduling duplicate retries.

## Existing safeguards verified in code

- Each account is assigned a named WebView profile (`mb_<accountId>`) and profile-specific cookie manager.
- The code checks that the WebView profile matches the requested account and fails closed if the WebView multi-profile feature is unavailable.
- Runtime automation is owned by a foreground service, with account-specific jobs, a watchdog, persisted active-run state, and tower-cycle scheduling.
- SharedPreferences files holding account/runtime data are excluded from configured cloud/device backup rules.

## Important limitations and follow-up risks

- **Profiles do not give accounts different IP addresses.** Every profile uses the device's network. The new queue serializes requests; it does not rotate IP addresses or bypass site restrictions.
- **Stored session cookies/CSRF data are not encrypted by the repository layer.** Backup exclusion reduces accidental transfer but does not encrypt data at rest. A future migration to Android Keystore-backed encryption should be designed with a recoverable migration path and tested before release.
- The application manifest allows cleartext HTTP traffic. Tightening that policy needs checking every navigation and resource dependency first, so it was not changed blindly in this pass.
- `MangaBuffAutomation.kt` is a very large file with embedded JavaScript and a stateful reader flow. Changes to chapter/history confirmation should continue to be small and require real-device regression tests.
- There is no comprehensive automated test coverage for account queue cancellation/recovery, profile-cookie isolation, renderer crashes, app process death, screen-off reading, or terminal-manga-to-catalog navigation. The log-buffer test is not a substitute for these integration tests.

## Verification performed

- Verified the branch head and fetched the changed service, ViewModel, profile-store, reader, helper, and test files from GitHub.
- Ran an isolated Kotlin check of the rolling-log algorithm: 2,000 entries retained, latest entry first, oldest retained entry `101` after appending `0..2100`.
- Added `AccountExecutionQueueTest` for serial execution, identity-safe queue tracking, and cancellation of a waiting account. These repository tests have been added but were **not executed in this environment**.
- The branch's `RecoveryBudgetTest` and `MarkReadAttemptGateTest` were reported by the previous contributor as passing under standalone `kotlinc` + JUnit; I have not independently rerun them.
- **Not run:** Android `assembleDebug`, Gradle unit tests, instrumentation tests, or Redmi/HyperOS real-device validation, because this environment has no Android SDK or Gradle executable.

## Suggested release gate

Before relying on a new APK, build it locally and test: two accounts started together; stop the queued account; stop/restart the active account; screen-off reading; renderer/WebView recovery; a final chapter showing “Таков конец…”; confirmed folder-3 mark; fallback after folder-mark timeout; and the subsequent catalog scan. Check that the event order in logs is plausible for each path.
