# Talaria 1.14.6 (versionCode 23)

Applied on top of the published Hermes/Talaria 1.14.3. Includes everything from the unpublished 1.14.4 and 1.14.5 builds.

## Hermes contract fixes (from a full docs and code cross-check against hermes-agent main, 2026-10-07)
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

## Security and robustness (from the external code audit)
- Encrypted storage retries after a corrupt keystore. A plain fallback is only used if that fails, and More shows a warning.
- Network security config: only system CAs are trusted, no user CAs.
- Capped streaming reads for REST, URL fetch (no redirects), pickers (25 MB) and uploads.
- Remote images in chat load on tap unless they're from your own server.
- Warnings for public plain-HTTP server URLs on Connect and More.
- RPC timeout cleanup.

## Not done yet (next)
- Gap-free reconnect (session.events.since), server-side queue replacing the phone queue, async result bodies, todo checklist, live subagent events, context meter and cost, pin/archive, session.branch/undo.
- Markdown streaming reparse performance, old-Android download filename sanitisation, full-size image decode.
- Not tested on a device.
