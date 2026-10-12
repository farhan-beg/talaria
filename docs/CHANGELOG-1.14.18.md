# Talaria 1.14.18 (versionCode 35)

## Fixed
- New chat no longer races a chat you've left: switching chats bumps a session generation, so a reply from the old one can't repaint over the new one, and "New chat" releases the old session immediately.
- Rejoining a chat the server is still waking no longer flashes "session not found": the app quietly re-attaches, retrying once a beat later, instead of showing an error notice and dropping the running turn's clock.
- A cold start can no longer override a chat you picked yourself with the last-remembered one.

## Stats
- Honest mid-turn stats: when you (re)join a turn already in progress, tokens and duration still show, but speed and first-token time show as unknown instead of wrong. Joined turns are also kept out of the stats cache.
