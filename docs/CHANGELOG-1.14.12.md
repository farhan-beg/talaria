# Talaria 1.14.12 (versionCode 29)

Fixes for the 11 open GitHub issues (#1 to #11). Builds clean; not yet tested against a live Hermes server.

1. Approvals: the card tells Hermes it's on screen (`approval.received`), so the timeout starts when you see it.
2. Vault: `vault.save_login` now shows a username + password card; `vault.code` and password-manager unlock cards name the site.
3. Chat menu: Branch chat (`session.branch`), Undo last turn (`session.undo`). Regenerate uses `session.undo` retry. While a reply runs, a Redirect chip (`session.redirect`) sits beside Steer and Queue.
4. Subagents update live from `subagent.*` events (status, tools, tokens, summary). One list read on open. Toggle to pause new spawns.
5. Todo checklist strip above the composer (`todo.updated`, `tool.complete.todos`, resume `todo_state`).
6. Run in background (`prompt.background`). The result lands in the chat and as a notification (`background.complete`).
7. Sessions: long-press to pin, archive or hide. New Archived filter, profile chip, pinned float to the top. Lists refresh on `sessions.changed`, Tasks on `cron.changed`.
8. Goal / loop / heartbeat strip with pause, resume and clear (`session.control*`). Context usage sheet (`session.context_breakdown`). Checkpoints sheet with diff and restore (`rollback.*`).
9. Hermes' toasts (`notification.show`/`clear`). Review summaries appear in the chat. Risky tool output gets a red badge (`tool.output_risk`).
10. One connection per saved server. Switching servers keeps the old socket and its open chat running. Its reply notification names the server, and tapping it opens that chat. Each chat is tagged with the server it came from.
11. More > For nerds > "Hermes sees my reactions" syncs `display.message_reactions`.
