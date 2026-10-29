# Talaria 1.14.21 (versionCode 38)

## Fixed
- Deleting an older chat no longer fails with `not_found`. When the server's REST delete can't mount a stored row (older history, or a row written by another client), Talaria falls back to the gateway's own `session.delete`. If the gateway still holds that chat as an idle, detached runtime, the app closes the runtime and retries; a chat that is mid-turn is never cut, the app refuses and asks you to stop it first.
- Renaming, archiving, hiding and pinning those same rows now survive too: when the REST edit is refused, the app uses the gateway's `session.title`, `session.archive` and `session.set_hidden` calls.
