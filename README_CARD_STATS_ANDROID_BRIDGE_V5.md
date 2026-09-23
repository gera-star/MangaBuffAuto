# Card Statistics Android Bridge V5

Fixes the V4 behavior observed on `/users/<id>/cards`.

- Restores the original userscript's batch strategy: up to 200 card IDs per `mbstat.space/cards?ids=...` request.
- Missing/empty batch records fall back to `/cards/{id}/users` and `/cards/{id}/offers/want`.
- Reuses WebView MangaBuff cookies for fallback requests.
- Adds Referer/Origin/User-Agent headers.
- Keeps a single permanent JS response dispatcher; no `window.__mbCardStatsCb_*` callbacks.
- Limits native HTTP concurrency to 4.
- Retries HTTP 429 up to 3 times.
- Keeps 24-hour card cache.
- Never substitutes `1/1` when a request fails.
- Does not modify EdgeBackGestureLayout, WebView navigation, AutomationRuntime or background keepalive.
