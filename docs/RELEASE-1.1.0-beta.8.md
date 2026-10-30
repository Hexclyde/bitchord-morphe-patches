# 1.1.0-beta.8

This testing release combines the BitChord 1.9 compatibility work with the
Unofficial Soulseek audio patch and its download tools. It targets the released
BitChord 1.9 phone APKs on Android 8 / API 26 or newer.

- **UI review:** screenshots and screen recording are enabled across beta.8,
  including setup pages and dialogs. Later builds default to protected capture.
- **Translations:** the Soulseek, debrid and peer-transfer pages, notifications
  and common transfer states follow BitChord's selected language in all 20
  supported translations, with right-to-left layout where appropriate.

- **Patches & tweaks** opens from BitChord's redesigned Settings page. Soulseek,
  peer downloads, debrid services, copy destinations and format preferences
  remain nested under that page. The managed download entries stay out of the
  Sources page.
- **Soulseek downloads** retain the chosen source file, format preferences and
  copy destinations. The download-route adapter handles BitChord 1.9's changed
  constructor and optimized coroutine layouts. Cancellation marks queued
  selections before interrupting an active transfer.
- **Debrid downloads** retain independent Real-Debrid, AllDebrid, Premiumize and
  TorBox switches. Each service starts disabled and uses the user's own API key.
  Provider cards start collapsed, expand one at a time, and use the themed
  switch and existing settings gear. Disabled providers hide their controls and
  disable the gear. First enable waits for a successful encrypted key save.
- **Peer downloads** retain Nostr coordination, direct UDP / QUIC transfer and
  the Tor, I2P and IPFS fallback runtimes. They remain download features.
- **Telegram downloads** handle the source probe introduced in BitChord 1.9
  using indexed file information. Channel playlists handle the new song field.
- **Smooth quality switching** handles BitChord 1.9's version-alignment
  parameters. Sing, headphone correction and DJ analysis cover Automix's
  separately rendered loop-mix audio paths.
- **Bit-perfect output** is reflected in the effects page. Sleep timers pause
  without an unavailable software-volume fade, Battery saver stands down, and
  the page identifies effects that do not reach that output.
- **DJ mode** waits for an active Automix transition before rearranging the queue.

Explicit Telegram and Soulseek file selections retain their original encoding;
the per-download quality chooser applies to catalogue downloads. A format label
or encoding probe cannot prove that a lossless file was never transcoded.

The patch bundle includes arm64-v8a, armeabi-v7a and x86_64 runtimes. Selecting
Soulseek on the BitChord 1.9 universal APK removes its unsupported 32-bit x86
native directory; that patched APK supports the three complete architectures.

This is a testing build. Automated checks do not qualify physical-device audio,
real provider accounts, public relay/NAT/overlay connectivity or Android
lifecycle behavior. See the [known limitations](KNOWN_LIMITATIONS.md) and
[download testing guide](SOULSEEK-TESTING.md).
