# Changelog

All notable changes to Talaria are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [1.14.5] - 2026-10-07 (versionCode 22)

### Added
- Chats started from Talaria are labeled "Talaria" with a phone icon in Home recents, History and the Conversations sheet, instead of "tui".
  - Hermes stamps every /api/ws session with source `tui`, the protocol the app speaks, and the app deliberately does not send a custom source: Hermes derives the agent platform from it, so a "mobile" source would change the toolsets and prompt.
  - The app remembers the session ids it creates (and follows compression rotations), so the label is local to the phone. Chats from before this version and other devices still show their Hermes source.

## [1.14.4] - 2026-10-07 (versionCode 21)

### Changed
- The line under the greeting on a new chat changes every time. It picks from a mix of general lines and lines for the time of day (morning, afternoon, evening, late night) and never repeats the previous one. The greeting still follows the phone clock.

## [1.14.3] - 2026-10-07 (versionCode 20)

### Fixed
- Sessions Hermes starts on its own (through `hermes chat -q` with source `cli`, or through its OpenAI-compatible API with source `api_server`) no longer show up as your chats.
  - `api_server` sessions always count as background/automation.
  - New setting, Settings → Sessions → "Treat CLI sessions as background" (on by default). Turn it off if you chat with Hermes from the terminal.
  - Both are excluded on the server (`exclude_sources`) and filtered on the phone, and the lists refetch when the setting changes.

## [1.14.2] - 2026-10-07 (versionCode 19)

### Fixed
- Subagent and cron sessions stay hidden when "Show subagent & cron sessions" is off, even after you've opened one.
  - Hermes keeps `source` as live routing state, so opening a cron run or delegate session from a client flips it (e.g. to `tui`/`desktop`). That let it slip past both the server's `exclude_sources` and the app's own filter.
  - Sessions are now classified by the immutable `created_source` (falling back to `source`), the `cron_<job>_<YYYYmmdd_HHMMSS>` run-id pattern, and `_delegate_from` markers.
  - With the toggle off the app requests twice the page size, so rows dropped on the phone don't leave recents short.

## [1.14.1] - 2026-10-07

### Chat stability
- After a reconnect or relaunch, the message that started a still-running turn reappears. It isn't saved on the server until the turn ends, so the app now restores it from the server's in-flight copy, or from what this phone sent.
- The running timer counts from when the server started the turn (`turn_started_at`), not from when the app reconnected or relaunched.
- Short lines like "Let me check." no longer pick up timing saved from an older turn.
- Finished turns loaded from history show "Worked for …", worked out from the server's message timestamps, instead of a step count.
- A close arriving late from an old connection can no longer kill the new one. Each socket gets a generation number, and callbacks from older sockets are ignored.
- A watchdog pings the server when a running turn goes quiet for 40 seconds and reconnects if the link is dead.

## [1.14.0] - 2026-10-07

Rolls up 1.12.0, 1.13.0 and 1.14.0.

### Chat
- Attached images show as thumbnails and files as cards; tap either to open a full preview.
- Web links are tappable, Hermes file paths open in the built-in viewer, and linked images render inline.
- Streaming is frame-paced and snaps to word boundaries, so text flows smoothly however fast or slowly the deltas arrive.
- Nerd stats cover the whole turn (tokens/sec, time to first token, total time, tokens), including turns folded into one work block, and survive reloads.
- Reactions on replies (❤️ 👍 👎 😂 ‼️ ❓) are stored on the server through `message.react`; agent reactions arrive live through `message.reaction`.
- `@` tagging in the composer completes files, folders and agent profiles through `complete.path`. Picking a folder drills into it.
- Code blocks show their language with Copy and Share. Draft fences (email, message, reply, post, prompt and similar) render as ready-to-send cards. Markdown tables render as tables.
- Reasoning from non-streaming providers is shown (`reasoning.available`, `message.complete.reasoning`).
- Error notices say when you can retry or when a rate limit resets.

### Sessions and reliability
- Reconnecting resumes a running turn: partial reply, pending approval and open questions all come back.
- If the server reclaims an idle session, the app resumes it on its own.
- Cancelled approvals disappear (`approval.cancelled`).
- Reactions use the row ids the server returns (`prompt.submit.user_row_id`, `message.complete.persisted_turn`).
- Session calls carry `profile`, so chats in non-default profiles resume correctly.

### Fast mode
- Fast mode now sticks. It is sent on `session.create`, resumes use `eager_build`, and the saved choice is reapplied once the agent is built. The toggle no longer reads back mid-build and flips itself off.

### Subagents
- Helper steps in a work block have a "Watch helper live" button that opens the Subagents sheet (list, tail, steer, stop).

### Recents
- Recents leaves out cron, kanban, tool, ACP, one-shot and recovered sessions, plus subagent and delegate children.

### Servers
- Save several Hermes servers, name each one, and switch from the server button on Home or from Settings. Each server keeps its own sign-in.

### Files
- A Files browser under More: browse, search, upload, make folders, delete, copy paths, preview text, Markdown, images and PDFs, then share, open with another app or save to Downloads.
