# Talaria 1.14.20 (versionCode 37)

## Fixed
- Chat history now shows what you typed, not the expanded form: messages with @file or @folder references no longer dump the whole attached file text into the bubble. You see your line, plus a chip per attachment.
- Messages sent to older servers (which stripped the @ref from the sentence) get their chips recovered from the attached-context block, so the bubble still shows what was sent.
- The running turn's own message no longer shows up twice when you resume a chat mid-turn: the pending message is compared in its typed form and built through the same chip path.
