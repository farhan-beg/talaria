# Talaria 1.14.10 (versionCode 27)

## Fixed: the chat switched when you came back to the app
- `session.reclaimed` is a global server broadcast whose frame `session_id` is always empty. The app treated empty as "mine", so whenever ANY chat idled out (another chat, another device) it resumed that one. It now matches the payload ids against the current runtime/chat only.
- Events without a session id can no longer move the current chat (`session.info` stored-id changes only from targeted events).
- Coming back to the foreground with a dead socket now re-attaches to the same chat (the foreground reconnect reset the retry counter, so it skipped the resume).
- Resume results are dropped if you opened a different chat meanwhile.

## New
- The open chat is remembered per server + profile, so after Android kills the app in the background a cold start reopens it instead of a blank chat.
