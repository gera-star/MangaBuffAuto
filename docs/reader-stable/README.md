# Stable MangaBuff Reader

## Snapshot

This directory marks the reader implementation that was verified in live MangaBuff logs on 2026-10-05.

**Stable commit:** `90b9d48ca15a8dee2030d96b1deabef79ac259d4`

**Stable branch:** `stable/reader-2026-10-05`

## Verified behavior

- Reader completes chapters only after MangaBuff read/history confirmation.
- `remaining=0` / document bottom is only an end candidate.
- `MB_IS_READ=true` alone is not sufficient.
- `LOCAL_HISTORY_STATE` is accepted for the CCL=2 local history-pool case.
- When MangaBuff submits the batch, `ADD_HISTORY_2XX` is required before completion.
- The daily quest counter (`X/75`) is diagnostic only and is never a per-chapter stop gate.
- Chapters are read sequentially within the selected manga.
- The current manga is finished before switching to another manga.
- The reader uses native finger-like swipes with movement verification and bottom stabilization.
- Lazy-loaded document growth is re-checked before chapter completion.

## Critical rule for future changes

Do not change the reader completion state machine, CCL/history confirmation, bottom stabilization, or navigation ordering without first creating a new tested snapshot/branch.

The intended completion order is:

`bottom reached`
→ `MangaBuff is_read=true`
→ `history confirmation`
→ `CHAPTER_CONFIRMATION`
→ `COMPLETION_ACCEPTED`
→ next chapter

## Restore point

If a future reader change breaks behavior, restore or compare against branch:

`stable/reader-2026-10-05`

and commit:

`90b9d48ca15a8dee2030d96b1deabef79ac259d4`
