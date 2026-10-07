# Talaria ⇄ Hermes cross-check, 2026-10-07

**Scope.** This is a read-only audit of Talaria (`dev.hark.hermes`) against Hermes Agent `main` as of 2026-10-07 ~04:00 UTC. It covers the gateway contract, server code, Hermes Desktop behaviour and the docs. I modified nothing under `hermes-android`.

**Talaria line numbers** come from a snapshot I copied at 04:12 UTC to `/workspace/work/xcheck-snap/hermes/` (same tree as `app/src/main/java/dev/hark/hermes/`).
- Another agent is editing `Gateway.kt` live. It is adding a usage-baseline fix (`usageKnownAtStart`), so lines after ~348 in the live file sit about 10 lower than the numbers quoted here.
- Function names are given so each spot can be found again.

**Hermes sources.**
- `/workspace/work/hermes-src/` is the 2026-10-06 22:56 UTC checkout.
- Files changed upstream since that checkout were re-fetched into `/workspace/work/xcheck-snap/hs/`: `tui_gateway/methods_session.py`, `session_workdir.py`, `server.py`, `contracts/sessions.py`, and `gateway-contract.openrpc.json`.
- Files not in the old checkout were also fetched there: `hermes_cli/web_server*.py`, `hermes_cli/web_routers/*`, `agent/notification_presentation.py`, `agent/turn_failure_copy.py`, `agent/history_commentary.py`, `agent/system_prompt.py`, `agent/prompt_builder.py`, `gateway/warning_notifications.py`, `tools/process_registry_notifications.py`, `tools/delegate_tool_progress.py`, `tools/async_delegation.py`, `tools/approval*.py`.
- Desktop paths are under `hermes-src/apps/desktop/src/`.

**Contract helper.** `python3 /workspace/work/xcheck-snap/q.py <method>|S:<Schema>|ev:<event>` prints params and result from the fresh OpenRPC file.

---

## 0. Contract verification: what checks out

These match the OpenRPC contract and the server code, so no action is needed:
- `session.create {cols, source, profile, fast}`.
- `session.resume {session_id, cols, source, inline_images, eager_build, profile}`. `source` is in `SessionResumeParams`.
  - On a cold resume, the source becomes the live record's source (`methods_session.py:638-643`, `_new_runtime_ids`).
  - A live-reuse resume keeps the original source (`_resume_reuse_live_locked`, `methods_session.py:884-899`).
- `session.history`, `prompt.submit` (incl. `truncate_before_row_id`, `truncate_before_user_ordinal`, `confirm_truncate`) and `user_row_id`.
- `message.react` / `message.reaction`.
- `image.attach_bytes {content_base64, filename}`, `pdf.attach`, `file.attach {name, data_url}` (with `ref_text`), `image.detach`.
- `subagent.list/tail/interrupt/steer`, `session.steer` (status `queued|redirected|rejected`), `prompt.btw` → `btw.complete`.
- `slash.exec`, `command.dispatch`, `complete.slash/path`, `commands.catalog`, `config.get/set`, `model.options`, `session.title/compress/interrupt`.
- Server requests `approval` / `clarify` / `sudo` / `secret` / `vault.unlock_prompt`, and the `{choice}`, `{answers}`, `{value}` result shapes.
- Event payloads: `message.start/delta/interim/complete` (incl. `persisted_turn`, `error_surface`, `response_reused`), `reasoning.available`, `tool.start/complete`, `approval.cancelled`, `session.reclaimed`, `request.cancel`.
- `TranscriptMessage` fields: `row_id`, `timestamp` (float seconds), `display_kind`, and `display_metadata` (object or JSON string; parsed by `parseReactions` and `machineNote`).
- `InflightTurn.user` is a string. Talaria accepts both a string and an object, which is fine.
- **REST:** every `/api/...` path Talaria calls exists with the method it uses. I checked them programmatically against all `@router` decorators in `hermes_cli/web_server*.py` and `web_routers/*.py`; `/api/auth/me` lives in `hermes_cli/dashboard_auth/routes.py`.
  - `/api/sessions` accepts `limit≤100`, `order=recent` and `exclude_sources` (`web_routers/sessions.py:177-205`).
  - Talaria's `HIDDEN_SOURCES` (`HomeScreen.kt:187`) now includes `acp` and `oneshot`.
- **Nothing Talaria calls is deprecated or renamed.** The earlier `tool.progress` listener is gone.

Everything below is a divergence.

---

## 1. Ranked findings (bugs and behaviour divergences)

### 1. [bug · high] `thinking.delta` is spinner and wait-notice text, but Talaria appends it to the reasoning
- **Talaria:** `Gateway.kt:389` (`"reasoning.delta", "thinking.delta" -> … reasoning = it.reasoning + t`). It is also in `LIVE_EVENTS` (`Gateway.kt:118`).
- **Hermes:**
  - `tui_gateway/agent_callbacks.py:117-123` (`_agent_thinking_update`) says: "Wait notices and the quiet spinner share this callback… They are not reasoning blocks".
  - Desktop `app/session/hooks/use-message-stream/gateway-event/message-stream.ts:195-205` says: "Most thinking.delta frames are kawaii spinner rewrites and stay out of the transcript". It routes them to `setSessionProviderWait` (the status row) only.
- **Effect:** spinner frames and "waiting for provider…" lines pile up inside the Work block's reasoning. They are persisted to `StatsCache` as reasoning, and they open an empty assistant bubble via `ensureAssistant()`.
- **Fix:** split the case.
  - `"thinking.delta" -> status.value = pl.s("text")`: replace, don't append, and don't call `ensureAssistant`.
  - Keep only `reasoning.delta` appending.
  - Clear `status` on the next `message.delta`, `tool.start` or `reasoning.delta`, the way desktop's `PROVIDER_WAIT_SUPERSEDING_EVENT_TYPES` does (`gateway-event/index.ts:68-79`).

### 2. [bug · high] `status.update` and `subagent.progress` open phantom turns that never close
- **Talaria:** `Gateway.kt:118` lists both in `LIVE_EVENTS`. `Gateway.kt:366-377` then sets `busy=true` and `turnStart`, and appends a `"Background work finished"` boundary Notice. Only `message.complete` or `error` clears `busy` (`:459`, `:466`).
- **Hermes:** these events arrive with no turn at all.
  - **Manual `/compress` on an idle chat:** `status.update {kind:"compressing"}` then `{kind:"status", text:"ready"}`, with no `message.complete` (`methods_session.py:2115,2138,2159`; `server.py:850-861`). Talaria's own `compress()` hits this every time: Stop stays lit, the queue never drains, and the activity line reads "ready".
  - **Process or kanban notifications:** `status.update {kind:"process"}` is emitted before the turn is claimed. When `display.background_process_notifications: off`, or while the user's Stop latch holds wakes, no turn follows (`session_notifications.py:540-560`, `:147-156`, `:441-443`).
  - **Async delegation children** relay `subagent.*` on the parent sid after the parent's turn ended (`tool_progress.py:452-475`, `tools/delegate_tool_progress.py:344-407`).
- **Desktop:** `gateway-event/status.ts:47-140` never sets busy from `status.update`. It switches on `kind`: `compacting`/`compressing` → phase on, `compacted`/`ready` → phase off, `process` → refresh process list, `goal` → goal card, fallback → system row. Subagent events only feed `store/subagents.ts`.
- **Fix:**
  - Drop `status.update` and `subagent.progress` from `LIVE_EVENTS`.
  - Handle `status.update` by `kind`:
    - `compacting`/`compressing` → show "Compressing…" without touching `busy`.
    - `compacted`/`ready` → clear `status`.
    - `process` → keep the text as a provisional label for the next server-started turn (see #4).
    - Otherwise → `status.value = text` only while `busy`.
  - Only `message.start`, `message.delta`, `reasoning.delta`, `tool.*` and `message.interim` should imply a live turn.

### 3. [bug · high] No turn settle on `session.info {running:false}`; muted turns never send `message.complete`
- **Talaria:** `applyInfo` (`Gateway.kt:585-601`) reads `running` only to adjust `turnStart`. `busy` is cleared only by `message.complete`, `error`, a socket drop, or the 6 s Stop fallback in `interrupt()` (`:993-1002`).
- **Hermes:**
  - Diagnostic notification turns run muted when `display.suppress_warning_notifications` is on (`prompt_turn.py:1131-1153`; `gateway/warning_notifications.py:65-75,90-101`).
  - `agent/notification_presentation.py:8-12` mutes `message.start`, `message.delta`, `message.complete`, `tool.*`, `status.update` and `error`.
  - The settled `session.info` is not muted and always carries `running` (`server.py:2399`). It is emitted in every turn's `finally` (`prompt_turn.py:1225` → `session_workdir.py:368-375`).
  - Desktop treats `session.info running:false` as the authoritative turn end and rehydrates when the terminal frame was missed (`gateway-event/session-info.ts:165-172, 325-475`).
- **Fix:** in `applyInfo`, when `pl["running"] == false && busy.value`, settle the turn the same way the `message.complete` tail does:
  - set `busy=false` and `status=""`;
  - end streaming bubbles and mark open Tools done;
  - set `turnStart=0`.
  - Then fire one `session.history` resync (#4).
- Guard it so it doesn't fire within ~2 s of this phone's own `prompt.submit`, before the server flips `running=true`. Desktop uses `turnLive`, set on `message.start`, for the same purpose.

### 4. [bug · high] Turns Hermes starts itself get the wrong boundary, and the transcript is never reconciled
- **Talaria:**
  - The comment at `Gateway.kt:369` ("a turn Hermes started itself … sends no message.start") is wrong.
  - On `message.start`, `Gateway.kt:380-386` inserts a generic `"Background work finished"` Notice whenever the phone didn't submit in the last 15 s.
  - Afterwards, `syncRowIds()` (`:636-646`) only text-matches assistant rows.
- **Hermes:** these turns do emit `message.start`, sometimes twice.
  - Notifications: `session_notifications.py:163-170` (`_notif_submit`), then `prompt_turn.py:1152`.
  - `/loop` wakeups: `session_notifications.py:296`.
  - Crash auto-continue: `session_auto_continue.py:123-130`.
  - `/heartbeat` ticks: `session_notifications.py:254` → `prompt_turn.py:1152`.
- **What each turn's user row really is:**
  - **Heartbeat process wakes** are `display_kind:"hidden"`; "no surface paints the row" (`session_notifications.py:489-493`; `tools/process_registry_notifications.py:352`). History drops hidden rows server-side (`session_history.py:299-301`).
  - **`/loop` and `/heartbeat` prompts** are persisted as plain user rows, so they reload as user bubbles.
  - **Async-delegation and process completions** carry a `display_text` such as "2 Background Processes Finished".
  - So Talaria's live label is wrong for every kind, and the live view and the reopened view disagree.
- **Desktop:** rehydrates from the stored session after every `message.complete` (`use-message-stream/index.ts:1062-1116`, `shouldHydrate`) and after a recovered `running:false` (`session-info.ts:470-473`).
- **Fix:** after each `message.complete`, and after a #3 settle, call `session.history {session_id}` once.
  - Rebuild the current turn's tail (or the whole list) through the `applySnapshot` row mapper. Keep live-only stats by carrying `startMs`, `firstMs`, `endMs` and `outTokens` over to matching `row_id`s.
  - This gives correct `row_id`s for every row, so delete the text-matching `syncRowIds`, keeping `persisted_turn` as the fast path. It also brings in the real boundary rows: hidden wakes vanish, loop prompts appear, `display_text` labels replace the generic one.
  - Until the resync lands, label a server-started turn with the last `status.update{kind:"process"}` text (#2) instead of the generic string. Insert no boundary at all when no such text arrived, because it may be a hidden heartbeat wake.

### 5. [bug · high] Desktop-only server requests reach the phone; "Decline" settles them with a bogus result
- **Talaria:**
  - `Gateway.kt:307-309` turns every server→client request into an `asks` card, after advertising `client.capabilities {server_requests:true}` (`:212`).
  - `ChatScreen.kt:681-692` (`AskCard` else-branch) renders any unknown method as a "Value" field. "Decline" sends a **result** `{"value":""}`.
- **Hermes:** `tui_gateway/server_requests.py:24-28,100,221-251`. The window-owned bridges `preview.read`, `preview.act`, `terminal.read`, `window.read` and `tour` must be answered only by the window showing the session. Every other client declines with an **error** `{code: 4404}` (NOT_SHOWN_CODE). The request settles early only once all answering clients have declined. `client.capabilities` returns `declines_not_shown: true` (`methods_voice.py:442-450`).
- **This happens when** the phone cold-resumes, or live-reuses, a chat that is also open in Desktop with the `desktop_ui` toolset, or one whose agent was built as `desktop`. Then:
  - (a) the agent waits out the full deadline when Desktop isn't showing the chat, because the phone never declines;
  - (b) a tap on "Decline" or "Submit" resolves the tool with junk and beats the real owner.
- **Fix:**
  - In `handle()`, for the method set {`preview.read`, `preview.act`, `terminal.read`, `window.read`, `tour`}, immediately send `{"jsonrpc":"2.0","id":<id>,"error":{"code":4404,"message":"not shown"}}` and never surface a card.
  - Add real handlers for `vault.code` (a `{value}` code entry) and `vault.save_login` (`{value: JSON.stringify({identifier,password})}`).
  - Answer `display.install.sudo` with error 4404.
  - For still-unknown methods, reply with a JSON-RPC error rather than a card.

### 6. [bug · med-high] Sending while busy can interrupt the live turn; the queue drain races server follow-ups
- **Talaria:**
  - `ChatScreen.kt:428-430` sends via `g.send(t)` when busy and attachments are present. `Voice.kt:156` calls `g.send(t)` unconditionally.
  - `send()` (`Gateway.kt:713-734`) posts `prompt.submit` with no `queued` flag and ignores `result.status`. It always appends a User bubble as a new turn.
  - The client queue drain (`Gateway.kt:814-825`) calls `send()` 250 ms after `message.complete`.
- **Hermes:** `session_auto_continue.py:400-452` (`_handle_busy_submit`). A busy `prompt.submit` follows `display.busy_input_mode`, whose default is `interrupt`.
  - A **text-only** payload, which includes `@file:` refs from `file.attach`, *redirects* the live turn: "Interrupt + queue" on agents without redirect support.
  - Images queue without interrupting.
  - `queued:true` "forces queue mode: a 'run after' message must NEVER become a live correction". The result `status` is `queued|steered|redirected`. Server follow-ups (goal judge, notification turns, queued-prompt drain) can start in the 250 ms gap.
- **Fix:**
  - Pass `queued: true` on every `prompt.submit` that isn't meant to steer: queue drain, busy sends with attachments, voice while busy.
  - Read `status`. On `"queued"`, keep the bubble in a "queued" state, and let the next `message.start` (or the #4 resync) promote it.
  - Better still, drop the phone-side queue and rely on the server queue. It is durable at accept (`session_auto_continue.py:437-441`) and visible on resume as `queued {user}`, so it survives the app being killed.

### 7. [bug · med] `failed_turn` boundary rows are assistant-role, so they render as an assistant reply
- **Talaria:** `applySnapshot` calls `machineNote` only for `role=="user"` (`Gateway.kt:488`). The `"assistant"` branch (`:492-498`) shows any non-blank text as a reply, and the `failed_turn` entry in `NOTICE_KINDS` (`:65`) is unreachable.
- **Hermes:**
  - `agent/turn_failure_copy.py:39-58`: `FAILED_TURN_DISPLAY_KIND` is applied to `role == "assistant"` rows.
  - `session_history.py:264-271,356-358` stamps `display_kind` on any role.
  - Desktop maps notice kinds to `role:'system'` for every role (`lib/chat-messages/hydration.ts:163-174,452`). It renders `failedTurnError(display_metadata)` (`error`, `error_surface`) as an error card (`hydration.ts:195-210,579-592`).
- **Fix:**
  - Apply `machineNote(display_kind, …)` before the role switch.
  - For `failed_turn`, emit `ChatItem.Notice(error=true)` with `display_metadata.error ?: error_surface.code`, and don't make it a turn boundary.
  - While there, honour `display_metadata.interrupted == true` on assistant rows (`hydration.ts:222-224`) with a "Stopped" marker.

### 8. [bug · med] Reopened chats show raw `@image:` and `@file:` lines in user bubbles
- **Talaria:**
  - `send()` builds `body = refs + "\n" + text` (`Gateway.kt:717-719`) and shows `📎 name` chips live.
  - `applySnapshot` (`:483,490`) puts history text verbatim into `ChatItem.User`. Images come back as `@image:<host path>` lines (`session_history.py:150-180`), and file attachments as the leading `@file:` refs Talaria itself sent.
- **Desktop:** lifts `@image:` refs and the leading `@file:` block into `attachmentRefs` chips (`hydration.ts:455-466`, `extractImageRefs` / `splitLeadingAttachmentRefs`).
- **Fix:** in the user branch of `applySnapshot`:
  - Strip trailing `^@image:` lines into `Attachment(kind="image", path=…)`.
  - Strip leading `@file:`/`@folder:` lines into `Attachment(kind="file", ref=…)`.
  - Render them with the existing chip row (`files=`).
  - Keep `raw` intact so edit and regenerate re-send the original.

### 9. [bug · med] The model switch reports success when Hermes asked for confirmation or deferred it
- **Talaria:** `setModel` (`Gateway.kt:773-779`) ignores the result, then sets `model.value = modelId` and posts "Switched to …".
- **Hermes:**
  - `config.set key:"model"` returns `confirm_required: true` and `confirm_message` *without switching* when selection guards flag the model: price, context loss or large cached context (`model_switch.py:262-277`; `methods_config_set.py:67-69,160-163`).
  - While a turn runs, the switch is stashed and returns `deferred: true` (`methods_config_set.py:86-91,122-123`).
  - The REST path in `AgentScreens.kt:188` already sends `confirm_expensive_model: true`.
- **Fix:**
  - If `r.b("confirm_required")`, show `confirm_message` in a dialog and, on yes, resend with `confirm_expensive_model: true`.
  - If `r.b("deferred")`, say "Switches after this reply".
  - Update `model` from the next `session.info`, not optimistically.
  - Show `warning` as a Notice.

### 10. [bug · med] Retry-on-error duplicates side effects, and JSON-RPC errors are treated as transport failures
- **Talaria:**
  - `handle()` turns every JSON-RPC `error` into `IOException` (`Gateway.kt:315`).
  - `rpc()` (`:282-289`) retries **any** method other than `prompt.submit`, `slash.exec` and `command.dispatch` after 400 ms on `IOException`.
- **Effects:**
  - `session.create` retries can mint two sessions; Hermes supports `idempotency_key` (`methods_session.py:350-445`).
  - `image.attach_bytes`, `file.attach` and `pdf.attach` retries queue the attachment twice. The first call succeeded if only the reply was lost (`methods_prompt.py:886-994` appends to `attached_images` each time).
  - `session.steer`, `prompt.btw` and `subagent.steer` retries duplicate text.
  - Deterministic server errors (4002, 4016, 4040, 5028) are retried pointlessly.
- **Fix:**
  - Use a distinct `RpcError(code, message)` for error frames and never retry those.
  - Retry transport failures only for idempotent reads (`session.history`, `config.get`, `model.options`, `commands.catalog`, `complete.*`, `subagent.list/tail`, `ping`) and for `session.create` with an `idempotency_key` minted once per `newChat()`.
  - Use the code (not message substrings) for the `eager_build` / `fast` / `service_tier` fallbacks at `:609` and `:695`.

### 11. [bug · med] `storedSid` doesn't follow compression rotation
- **Talaria:** `applyInfo` (`Gateway.kt:585-601`) ignores `session.info.stored_session_id`. `storedSid` changes only on create, resume or reclaim (`:130-131,474-475`).
- **Hermes:** compression rotates `session_key` mid-session (`session_compression.py:298-316`). Every later `session.info` carries the new `stored_session_id` (`server.py:2402`).
- **Effect:**
  - The "started on this phone" mark (`store.markMine`, `Gateway.kt:131`) never reaches the continuation row.
  - `Files.kt:87` sends a stale `session_id` to `/api/fs/download`.
  - Resume still works only because the server rebinds to the compression tip (`methods_session.py:832-843`).
- **Fix:** in `applyInfo`, apply `pl.sn("stored_session_id")?.takeIf { it.isNotBlank() }?.let { storedSid = it }`. The setter already carries `markMine` across.

### 12. [bug · low-med] Turn token stats lack a usage baseline from `info.usage`
- **Talaria:**
  - `newChat()` and `resume()` set `usage.value = null` (`Gateway.kt:690,706`).
  - `applyInfo` never reads `info.usage`.
  - Per-turn tokens are `usage.output@complete − usage.output@start` (`:349,424`).
- **Hermes:**
  - `Usage` counters are cumulative for the live agent (`server.py:2187-2196`). They are zero only on a fresh build (`agent/agent_init.py:2347`).
  - A live-reuse resume keeps them (`methods_session.py:884-899`), and every `session.info` / `info` carries `usage` (`server.py:2403`).
- **Effect:** the first turn after reattaching to a live agent counts the whole session's output, which inflates tok/s.
- **Note:** the in-flight edit adds `usageKnownAtStart` plus a plausibility clamp. That hides the symptom but drops exact counts.
- **Fix:** in `applyInfo`, do `pl.o("usage")?.let { usage.value = it }`, so the baseline is always the server's real cumulative value.
- **Also:**
  - `outOf` reads `output_tokens` and `completion_tokens`, which `Usage` doesn't have. The real keys are `output` and `completion`. This is harmless, but should be simplified to `output`.
  - Consider showing `usage.avg_tps`, the server's rolling 10-call throughput (`server.py:2213-2221`).

### 13. [bug · low] Clarify: no multi-select, and choices keep the "(recommended)" suffix
- **Talaria:** `ChatScreen.kt:660-679` has a single-choice `ChipRow` and sends `choice` text verbatim.
- **Hermes:**
  - `ClarifyQuestion.multi_select` (contract `S:ClarifyQuestion`; `server.py:1413-1422`).
  - Desktop sends multi-select answers as `JSON.stringify([...choices, draft])` and strips the recommended label with `bareChoice` (`components/assistant-ui/clarify/pending.tsx:121-140`; `store/clarify.ts:31-32`).
  - Desktop locks answers per question with `clarify.lock {request_id, question_id, answer}`, which survives a timeout (`server_requests.py:20-22,296`).
- **Fix:**
  - Multi-select chips when `multi_select`, answered as a JSON array string.
  - Strip the recommended suffix.
  - Optionally call `clarify.lock` per question as it's answered.

### 14. [low] Steer rows split turns on reload
- **Talaria:** a live steer is a Notice (`Gateway.kt:807`), but history returns it as a user row with `display_kind:"steer"` (`session_history.py:318-321`). `applySnapshot` makes it a `ChatItem.User`, and `foldTurns` (`Turn.kt:23`) treats that as a turn boundary, which splits the Work block.
- **Fix:** map `display_kind=="steer"` to the same non-boundary `↪ Steered:` Notice.

### 15. [low] Identifying as `source:"mobile"` has side effects worth knowing
- **No platform hint:** `PLATFORM_HINTS` has no `mobile` entry (`agent/prompt_builder.py:682-760`), so the model is never told that `MEDIA:` renders on the phone.
- **Launch directory treated as a workspace:**
  - For non-`desktop` sources, the server's launch cwd counts as a real workspace for context-file discovery (`session_workdir.py:219-224`; `agent/system_prompt.py:722-723`).
  - On a cold resume, the source becomes `mobile` (`methods_session.py:638-643`, `1007-1009`). Reopening a Desktop chat on the phone therefore swaps the platform prompt, drops `desktop_ui`, and changes context files. The next turn misses the prompt cache.
- **Fix (judgment call):**
  - Either upstream a `"mobile"` hint, or accept and document it.
  - Optionally pass an explicit `cwd` on `session.create` when the user picks a project.
  - Nothing breaks today.

---

## 2. Feature gaps, ranked by value to a phone user

Each entry lists the RPCs and events from the OpenRPC contract (`q.py` prints the shapes). None of them appear in the Talaria source (checked by grep).

1. **Gap-free reconnect.**
   - Contract: `gateway.ready.replay_epoch`; `session.events.since {session_id, last_seen}` → `{events, latest_seq, truncated, epoch, open_requests}`.
   - Today every reconnect does a full `session.resume` and rebuilds the list (`Gateway.kt:214-216`), dropping live stats.
   - Plan: store the last event `seq` per session, replay on reconnect, and fall back to resume when `truncated` is set or the epoch changes.
2. **Server-side queue that survives the app being killed.**
   - Contract: `prompt.submit {queued:true}` → `status:"queued"`; resume `queued {user}`; history persists the queued row at accept.
   - Pairs with finding #6.
3. **Async completion result bodies.**
   - Desktop shows "collapsed timeline disclosures" whose body is the report (docs `website/docs/user-guide/desktop.md` §Chat; `hydration.ts:240-270,603-606`, `asyncResultBody`).
   - The boundary row's `content` holds it; Talaria shows only the one-line label.
   - Plan: keep `content` on the boundary Notice and expand on tap.
4. **Task checklist.** Contract: `todo.updated {todos, revision}`, `tool.complete.todos`, resume `todo_state`. This is desktop's "Task progress above the composer".
5. **Live subagent panel without polling.**
   - Contract: `subagent.spawn_requested/start/thinking/tool/progress/complete` (`goal`, `status`, `tool_count`, `output_tail`, `summary`, tokens), `delegation.status`, `delegation.pause`, and `subagent.list.delegations[]` for failed spawns.
   - Today Talaria polls every 2.5 s while the sheet is open (`ChatScreen.kt:834-839`).
6. **Context meter and cost.**
   - Contract: `session.context_breakdown` → `{categories[], context_percent, context_used, context_max}`.
   - The `Usage` fields `context_percent`, `cost_usd`, `cache_hit_pct` and `avg_tps` already arrive. Talaria shows only `total` (`ChatScreen.kt:243`).
7. **Live session list, pin and archive.**
   - Contract: the `sessions.changed` event (refresh instead of polling).
   - REST: `PATCH /api/sessions/{id} {archived, hidden, pinned, unread}` (`web_routers/sessions.py:892-925`) and `archived=only` for an archive view. Pinned rows are exempt from auto-archive.
8. **Background tasks with a notification.** Contract: `prompt.background {session_id, text}` → `{task_id}`, then `background.complete {task_id, text}`.
9. **Real branch, undo and retry.**
   - Contract: `session.branch {session_id, name?, count?, idempotency_key}` → new `session_id` and `stored_session_id`; `session.undo {intent: undo|retry}`.
   - Today "regenerate" is an edit-truncate of the last message.
10. **Goals, loops and heartbeat controls.** Contract: `session.control.read`, `session.control {action, args}`, the `session.control.update` event, and `status.update` kinds `goal`, `loop`, `heartbeat`.
11. **Checkpoints and rollback.** Contract: `rollback.list/diff/restore`.
12. **Out-of-band notices.**
    - Contract: `notification.show/clear` (credential warnings etc., emitted by `agent_callbacks._agent_notice_update`), `notice {message}`, `review.summary {text}` (desktop shows it as a persistent memory-write row, `status.ts:175-205`), and `tool.output_risk` for a security badge.
    - Talaria drops all of these.
13. **Server STT/TTS.** REST: `POST /api/audio/transcribe`, `POST /api/audio/speak`, `GET /api/audio/voice-config` (`web_routers/audio.py:83,146,292`). These are better than on-device for the voice screen.
14. **Hand off to a messaging app.** Contract: `handoff.request {session_id, platform}` and `handoff.state`.
15. **Image generation** (`image.generate`) and the **MoA progress** events (`moa.*`): lower priority.

---

## 3. Notes and limits

- **What was and wasn't checked:**
  - Each finding was checked against the cited lines.
  - I did not run the app or a live gateway.
  - Severity reflects user impact as read from the code.
- **Untested assumptions:**
  - #5 assumes a Desktop window can share a session with the phone. Both attach to the same live record on reuse (`_resume_reuse_live`), and `server_requests._clients(sid)` counts every answering transport.
  - In #6, the default `busy_input_mode` comes from the docstring of `_handle_busy_submit` (`session_auto_continue.py:402-405`). I did not read the config default itself.
- **Earlier gap notes:** `/workspace/work/hermes-gap-audit.md` still stands for fast mode (now mostly addressed by `fast` on create, `eager_build`, and the re-assert in `applyInfo`) and for per-call `profile`. Session RPCs resolve by runtime sid, so a missing `profile` on `prompt.submit` and the attach calls is harmless (`server.py:1243-1255`).
