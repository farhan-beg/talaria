# Talaria 1.14.17 (versionCode 34)

## While Hermes works
- Live notes: while a turn runs, the newest note Hermes writes between steps ("Found the databases. Let me look inside…") shows under the working block as it happens, then folds into "Worked for" when the answer lands, so the finished reply stands alone.
- More > While Hermes works: choose Show, then fold (default), Keep in reply (the old Full reply behaviour), or Hide. "Open steps while working" expands every tool call live and folds when the answer lands.
- The folded header now says what's behind the tap ("4 steps · 2 notes"), and the live block animates as notes arrive.

## Fixed
- Resuming a mid-turn chat no longer shows committed notes twice: only the uncovered tail of the live stream is appended.
- Duplicate event frames from overlapping sockets are dropped (per session + seq, 30 second window).
