# Talaria 1.14.16 (versionCode 33)

## Connection and sign-in
- VPN/Tailscale reconnect fix: the app now tracks the default network and ignores the stale "old network lost" event that used to kill a freshly reconnected chat. After a network switch it ping-probes the live socket instead of assuming it died.
- The chat socket opens with a single-use `/api/auth/ws-ticket` (minted over REST with an expired token refreshed first; falls back to `?token=` on older servers).
- Socket 401/403 and close 4401/4403 now recover with a bounded sign-in retry; "Tap to reconnect" resets backoff and retries the token refresh too.

## Diagnostics and reporting
- New App diagnostics screen (More > Help): a redacted on-phone log of connection, sign-in and request events, with filters, search, copy, share and clear. Verbose request logging is a toggle.
- Crashes are captured and offered for reporting on the next launch.
- New "Report a problem" screen: sends the report (log, device, network, optional Hermes error log and screenshot) to the developer by email, over an HTTPS endpoint when the build has one, or as a GitHub issue. Everything is previewable before it's sent, and tokens plus passwords are scrubbed from the log.
