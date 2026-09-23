# V11.1 Background KeepAlive update

- Fixed MainActivity compile error caused by self-reference to `container` inside its own `apply` block.
- Persistent automation now keeps a partial CPU wake lock while Auto Mode is ON.
- Foreground service remains `START_STICKY` and restores the persisted Auto Mode state.
- Removing the Activity task no longer counts as stopping automation; the foreground service requests a restart when Auto Mode is enabled.
- Existing WebView/runtime persistence and native background reader heartbeat are preserved.
- Edge swipe history behavior is unchanged.

Important: OEM battery restrictions can still terminate an application at the OS level. On HyperOS, keep Auto-start enabled and Battery/Background activity set to No restrictions for the app.
