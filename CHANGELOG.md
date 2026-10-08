# Changelog

All notable changes to Talaria are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [1.14.14] - 2026-10-08 (versionCode 31)

### Bots
- New Bots screen (Home robot icon, or Control center): every profile as a bot with avatar, @handle, the last line of its chat and a green "working" badge; pinned first, then sections; search; hidden toggle; long-press to pin, hide, edit or delete.
- Forever-chat: tap a bot to open its one "Bot Chat" (resolved server-side by title, the same chat Desktop has), running on that bot's own profile; survives app kills; header shows avatar and name, Rename becomes "All bots".
- New bot: name, mission, colour, shape and optional SOUL; starts with the main profile's model and keys.
- Edit bot: name, mission, colour, shape, photo (cropped to 512 px), section and SOUL.md; the look syncs through the same profile metadata Desktop reads, with compare-and-swap so two devices saving at once don't overwrite each other.
- Teammate messages show as that bot's bubble with its avatar.

Not yet: Desktop's multi-bot group rooms; per-bot cron and screen panes.

## [1.14.13] - 2026-10-08 (versionCode 30)

- Links in chat open with a single tap: text selection and the bubble's long-press used to swallow the tap. Web links open in your browser or the app that handles them; file paths open in the viewer. Long-press still selects text and opens the menu.
- Bare `www.` links are now tappable too.
- Same fix for links in headings, list items and tables.

## [1.14.12] - 2026-10-08 (versionCode 29)

Fixes for the 11 tracked issues (#1-#11):

- Approvals: the card tells Hermes it's on screen (`approval.received`), so the timeout starts when you see it.
- Vault: `vault.save_login` asks with a username + password card; `vault.code` and password-manager unlock cards name the site (and show the hint when given).
- Chat menu: Branch chat (`session.branch`), Undo last turn (`session.undo`); regenerate now uses the `session.undo` retry; while a reply runs, a Redirect chip (`session.redirect`) sits beside Steer and Queue.
- Subagents: live updates from `subagent.*` events (status, tools, tokens, summary), one list read on open, and a toggle to pause new spawns.
- Todo checklist strip above the composer (`todo.updated`, `tool.complete.todos`, resumes from `todo_state`).
- Run in background (`prompt.background`); the result lands in the chat and as a notification (`background.complete`).
- Sessions: long-press to pin, archive or hide; an Archived filter, a profile chip, pinned chats float to the top; lists refresh on `sessions.changed`, Tasks on `cron.changed`.
- Goal, loop and heartbeat strip with pause, resume and clear (`session.control*`); context usage sheet (`session.context_breakdown`); checkpoints sheet with diff and restore (`rollback.*`).
- Hermes' toasts (`notification.show` / `notification.clear`); review summaries appear in the chat; risky tool output gets a red badge (`tool.output_risk`).
- One connection per saved server: switching keeps the old socket and its open chat running, notifications name the server and open the right chat on tap, and each chat is tagged with its server.
- More > For nerds: "Hermes sees my reactions" syncs `display.message_reactions`.

## [1.14.11] - 2026-10-07 (versionCode 28)

### Fixed
- Wrong part of the reply showed: when Hermes replied, then used a tool (like saving to memory) and added a closing line, only the closing line appeared and the real reply was hidden in "Worked for". Everything written in a turn now shows in the reply bubble, in order; tools and thinking stay folded in "Worked for". Copy and regenerate cover the full reply.

### Added
- More > For nerds > "Full reply" switch (on by default); turn it off for the old behaviour.

## [1.14.10] - 2026-10-07 (versionCode 27)

### Fixed
- Chat switched when returning to the app: `session.reclaimed` is a global server broadcast whose frame `session_id` is always empty, and the app treated empty as "mine", so any chat idling out (another chat, another device) could replace the open chat. It now matches the payload ids against the current chat only.
- Events without a session id can no longer move the current chat (stored-id changes only from targeted events).
- Coming back with a dead socket now re-attaches to the same chat (a reset retry counter used to skip the resume).
- Late resume results are dropped if you opened a different chat meanwhile.

### Added
- The open chat is remembered per server + profile, so a cold start reopens it instead of a blank chat.

## [1.14.9] - 2026-10-07 (versionCode 26)

- Composer: live markdown styling (markers dimmed, offsets 1:1), format bar while typing (bold, italic, strikethrough, inline code, code block, heading, bullets, numbers, checklist, quote, link) with toggle-off on re-tap, and a rendered preview. Slash commands stay plain.
- Sent messages render markdown in the bubble; Copy still copies the raw text.
- Renderer: ~~strike~~, ***bold-italic***, __bold__, nested inline styles, backslash escapes, <autolinks>, task lists [ ]/[x], multi-line and nested blockquotes, bullet continuation lines, ~~~ fences, setext headings, trailing-# headings, spaced rules.
- More > For nerds: "Markdown in my messages" switch (on by default).

## [1.14.8] - 2026-10-07 (versionCode 25)

- New More > Voice page: choose Phone or Hermes voice; Hermes provider picker (from server schema), per-provider voice/model/speed fields saved via PUT /api/config tts.*; ElevenLabs voice list from /api/audio/elevenlabs/voices; provider API key status/set via /api/env; test playback.
- Phone voice: installed-voice picker, speed and pitch sliders, link to Android TTS settings.

## [1.14.7] - 2026-10-07 (versionCode 24)

- Voice mode: Phone voice / Hermes voice switch (also in More). Hermes voice reads replies through /api/audio/speak using the server tts: provider, sentence-chunked with prefetch, falls back to phone TTS with a visible note on failure.

## [1.14.6] - 2026-10-07 (versionCode 23)

Applied on top of the released 1.14.5.

### Hermes contract fixes (from a full docs and code cross-check against hermes-agent main, 2026-10-07)
- Identifies as `source: "mobile"` on session.create/resume, like hermes-go, not `tui`.
- `thinking.delta` (spinner and "waiting for provider" frames) now only drives the status line and no longer goes into reasoning.
- `status.update` and `subagent.progress` no longer mark the chat busy. Fixes Stop staying lit after /compress and after process notifications or async subagent chatter. status.update is handled by kind (compressing, ready, process).
- `session.info {running:false}` now settles the turn, which covers muted notification turns that never send message.complete.
- Turns Hermes starts itself (process completion, async delegation, /loop, heartbeat): the live label comes from the server's process notice. After the turn it's reconciled with session.history: hidden wakes vanish, /loop prompts become user bubbles, and display_text labels are used.
- Desktop-only server requests (preview.*, terminal.read, window.read, tour, display.install.sudo) are declined automatically with 4404 instead of showing a bogus card. Unknown requests get a JSON-RPC error. vault.code is supported.
- Sending while a turn runs (with attachments, voice, or a queue drain) passes `queued: true`, so it can't interrupt the live reply.
- History: `failed_turn` assistant rows are error notices, not replies. `interrupted` replies get a Stopped marker. Steer rows stay inside their turn. `@image:` / `@file:` refs become attachment chips.
- Model switch respects `confirm_required` with a confirm dialog, `deferred` ("switches after this reply") and `warning`.
- JSON-RPC errors are a distinct RpcError and never retried. Only idempotent reads are retried after a dropped socket. session.create carries an `idempotency_key`.
- storedSid follows compression rotation through session.info.stored_session_id.
- Token stats: the usage baseline comes from session.info.usage. A delta against an unknown baseline, or an implausible one, falls back to the streamed estimate (fixes absurd tok/s). Repeated message.start inside one turn no longer resets the clock.
- Clarify: multi-select questions are supported (sent as a JSON array), and the "(recommended)" suffix is stripped.

### Security and robustness (from the external code audit)
- Encrypted storage retries after a corrupt keystore. A plain fallback is only used if that fails, and More shows a warning.
- Network security config: only system CAs are trusted, no user CAs.
- Capped streaming reads for REST, URL fetch (no redirects), pickers (25 MB) and uploads.
- Remote images in chat load on tap unless they're from your own server.
- Warnings for public plain-HTTP server URLs on Connect and More.
- RPC timeout cleanup.

### Not done yet (next)
- Gap-free reconnect (session.events.since), server-side queue replacing the phone queue, async result bodies, todo checklist, live subagent events, context meter and cost, pin/archive, session.branch/undo.
- Markdown streaming reparse performance, old-Android download filename sanitisation, full-size image decode.
- Not tested on a device.

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
