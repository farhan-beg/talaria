# Talaria

An independent Android client for Hermes Agent servers.

Snapshot of version 1.9.0 (versionCode 11). Not affiliated with Nous Research.

## Features

- Chat with streaming replies, reasoning traces, and tool-call rendering
- Session history, resume, and live reconnect over the Hermes gateway WebSocket
- Automations (scheduled prompts), webhooks, profiles, and channel management
- Admin panels: models, skills, keys, MCP servers, memory, logs, system
- Sign-in with dashboard credentials and native token refresh; encrypted local storage
- Voice input and text-to-speech playback
- Customizable look: palettes, glass effects, motion, and ambient background

## Build

Requires JDK 17 and an Android SDK (compileSdk 36, build-tools 35.0.0 or newer).

    ./gradlew :app:assembleRelease

The release build is unsigned unless a `keystore.properties` file is present.

## Status

Early snapshot; features and structure are still changing.
