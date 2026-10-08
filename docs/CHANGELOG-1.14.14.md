# Talaria 1.14.14 (versionCode 31)

## Bots screen (robot icon on Home, or Control center -> Bots)
- Every Hermes profile shows up as a bot with its face, @handle, the last line of its chat, and a green "working" badge while it's busy.
- Pinned bots come first, then sections, just like on Desktop. There's search, and a toggle to show hidden bots.
- Long-press a bot to pin, hide, edit or delete it.

## Forever-chat
- Tap a bot to open its one "Bot Chat". The server finds it by name, so it's the same chat Desktop has.
- It runs on that bot's own profile and doesn't change the profile the app manages. It also comes back if Android kills the app.
- The header shows the bot's avatar and name. Rename is replaced by "All bots", so the chat keeps its title.

## New bot
- Give it a name, what it does, a colour and a shape, plus an optional SOUL.
- It starts with the main profile's model and keys, and can say hello straight away.

## Edit bot
- Change its name, mission, colour and shape, or give it a photo (cropped to 512 px).
- You can also set its section and edit SOUL.md.
- Its look syncs through the same profile metadata Desktop reads, and two devices saving at once won't overwrite each other.

## Teammate messages
- When another bot messages this one, it shows as that bot's bubble with its avatar, not as yours.

## Not yet
- Desktop's multi-bot group rooms.
- Per-bot cron and screen panes.
