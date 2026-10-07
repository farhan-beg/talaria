# Talaria: handover notes

Talaria (`dev.hark.hermes`) is a native Android client for Hermes Agent, written in Kotlin with Jetpack Compose. It talks to the Hermes dashboard over REST (`/api/...`) and the same WebSocket JSON-RPC gateway the desktop app uses (`/api/ws`).

Current version: **1.14.1**, versionCode **18**. It is built and signed but has **not been tested on a device**.

## Layout
- `data/Api.kt`: REST client, base URL, token refresh.
- `data/Auth.kt`: dashboard sign-in (basic/password providers).
- `data/Store.kt`: encrypted prefs, the saved server list (`servers`, `activeId`, `switchTo`, `addServer`, `renameServer`, `removeServer`), profile, theme, fast preference.
- `data/Gateway.kt`: the WebSocket JSON-RPC client. It handles chat state, streaming, server requests (approval, clarify, sudo, secret, vault unlock), reactions, `@` completion, run settings (fast/reasoning/yolo), reconnect and resume.
- `data/StatsCache.kt`: keeps each turn's timing so stats survive reloads.
- `data/Turn.kt`, `TurnService.kt`: the foreground service with the live notification and Stop button.
- `ui/screens/ChatScreen.kt`: chat, composer, slash and `@` hints, smooth streaming (`rememberSmoothText`), work blocks, reactions, Subagents sheet.
- `ui/Components.kt`: Markdown, code blocks with Copy/Share, draft cards, tables.
- `ui/screens/Files.kt`: Files browser and viewer.
- `ui/screens/HomeScreen.kt`: Home, the Recents filter (`HIDDEN_SOURCES`, `isSubagent`, `isAutomation`), the server switcher.
- The other screens cover Sessions, Cron, Agents/Profiles, Connections, Insights, Admin and Settings.

## Building
- JDK 17, Android SDK platform 36, build-tools 35.0.0, Gradle 8.11.1 (the wrapper is in the repo).
- `./gradlew assembleRelease`. If `keystore.properties` exists at the root, the release APK is signed with it; otherwise it builds unsigned.
- `keystore.properties` holds `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Never commit it or the `.jks` file; `.gitignore` already excludes both.

## Signing key
Every release so far (1.0.0 to 1.14.0) is signed with `signing/hermes-release.jks`, certificate `CN=Hermes Mobile`. **Keep using this key.** Android refuses an update signed with a different one, so users would have to uninstall and lose their data. Store it as GitHub Actions secrets:
- `KEYSTORE_BASE64`: `base64 -w0 signing/hermes-release.jks`
- `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`: from `signing/keystore.properties`

In CI, decode the keystore to the repo root as `hermes-release.jks` and write a `keystore.properties` before running `assembleRelease`. Keep a private offline backup of the key; if it's lost, users can't receive updates.

## Hermes contract
`docs/hermes-gap-audit.md` maps every gateway method, event and server request against what the app uses. It was written against `NousResearch/hermes-agent@main` on 2026-10-07.

### Done from the audit
Fast mode persistence, `profile` on session calls, resuming in-flight turns (inflight, pending_approval, open_requests), `reasoning.available`, `approval.cancelled`, `session.reclaimed`, `error_surface`, row ids from `persisted_turn` and `user_row_id`, reactions, `@` completion with folder drill-in, and the Recents filter (adds acp and oneshot).

### Still open (suggested order)
1. Send `approval.received` when an approval card renders.
2. Answer the `vault.code` and `vault.save_login` server requests.
3. Make branch, undo, retry and redirect real calls (`session.branch`, `session.undo`, `session.redirect`) instead of slash text.
4. Live subagent events (`subagent.start/progress/complete/...`) instead of polling `subagent.list`.
5. A todo checklist from `todo.updated`.
6. Background tasks (`prompt.background` and `background.complete`) with a notification.
7. Session archive, pin and hide (`PATCH /api/sessions/{id}`), a profile chip on rows, and refresh on `sessions.changed`.
8. A context meter (`session.context_breakdown`); goals, loops and heartbeat (`session.control*`); checkpoints (`rollback.*`).
9. Toasts from `notification.show` and `notification.clear`.
10. Keep one live connection per saved server and route each chat to its owner. Right now only the active server is connected.
11. The agent only sees your reactions when the server config `display.message_reactions` is true. Consider offering it as a setting.

## Test checklist
- Install over 1.13.0 and confirm you're still signed in. Your server should appear under Settings → Servers.
- Add a second server, switch between the two, rename one and remove one.
- Turn fast mode on, send a few messages, open another chat and resume an old one. It should stay on.
- React to a reply, reload the chat and confirm the reaction is still there.
- Type `@` and `@src/`, pick a folder, then pick a file.
- Background the app mid-turn for 30 seconds or more, then come back. The reply should continue.
- Confirm Recents shows no cron or subagent sessions.
