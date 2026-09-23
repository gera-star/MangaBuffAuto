# MangaBuffAuto V9.5 — launcher icon fixed

Based on V9.4 SOCIAL_LOGIN_VK_RU_FIXED.

Changes:
- Added launcher icon resources.
- Added adaptive icon for Android 8+.
- Added ic_launcher and ic_launcher_round to AndroidManifest.xml.
- Added drawable/ic_launcher_foreground.png.
- Added launcher background color.
- No automation logic, WebView logic, profile logic, social-login logic, ReadTask, StatsInspector, ProtectionGuard, or AutomationState changes.

The previous AAPT error about missing drawable/ic_launcher_foreground is resolved by providing the referenced drawable resource.
