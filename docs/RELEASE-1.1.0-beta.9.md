# 1.1.0-beta.9

This testing release builds on the completed beta.8 bundle for BitChord 1.9.
Its new work focuses on the **Unofficial Soulseek audio** patch: initial search
results, opt-in compatible-peer discovery and bounded public-profile exchange.
The existing playback patches and Unofficial Telegram integration are retained.

- **Initial Soulseek results:** a preferred-format result can settle the first
  shortlist before the full 12-second search window. Without a result in the
  highest enabled format, search keeps its full budget. A short-lived bounded
  query cache is tied to the login session and complete format policy. Peer speed
  and free-slot claims remain unverified hints; downloaded bytes still undergo
  the existing exact-source and encoding checks.
- **Compatible-peer discovery:** a new nested screen under **Patches & tweaks →
  Peer downloads → Connection setup** starts disabled. An explicit ten-second
  scan reads recent signed app profiles from configured Nostr relays even with
  zero paired peers. Publishing your own public profile is a separate opt-in
  while the screen is open. It publishes no files, private contacts, endpoints
  or grants. Stopping discovery or closing the page stops renewals immediately;
  already signed profiles can remain discoverable on relays and in peer caches
  until their expiry, at most five minutes after creation. This is not immediate
  retraction, and relays may retain expired copies that the app will reject.
- **Peer exchange:** already paired clients negotiate support before exchanging
  small batches of the original signed public profiles. Candidates are bounded,
  deduplicated and expired. A found key never becomes trusted automatically and
  cannot acquire file access from a referral.
- **Download tools:** Real-Debrid, AllDebrid, Premiumize and TorBox keep their
  individual switches and account keys. Direct UDP / QUIC and I2P, Tor and paired
  IPFS remain the selected-file fallback paths. Phone/Telegram/Both destinations,
  format ordering and byte-preserving publication remain in place.
- **UI review:** the discovery screen follows BitChord's cards, switches,
  settings gear and compact disabled controls. New download UI and common states
  follow all 20 supported translations, including RTL layout. Screenshots and
  screen recording remain enabled throughout beta.9, including setup and dialogs.

This is app-provider discovery, not BitTorrent DHT/PEX or a public music index.
Ordinary Soulseek users do not gain alternate transport routes automatically.
Finding many profiles does not establish available recordings or faster file
transfers. Explicit pairing and selected-file consent remain required; automatic
recording matching, automatic sessions and multi-provider swarming are not added.

The bundle targets the released BitChord 1.9 phone APKs on Android 8 / API 26 or
newer, with arm64-v8a, armeabi-v7a and x86_64 runtimes. Selecting Soulseek on the
1.9 universal APK removes the unsupported 32-bit x86 native directory, retaining
the three complete architectures. Soulseek's existing 1.8.1 compatibility is
retained separately from the combined bundle's 1.9 application target.

## Qualification

Release validation is recorded in the publishing package's `verification.json`.
All three Android native runtimes built successfully. Validation passed 88
behavioral UI tests, 31 Python tests, the complete host regression suite and ten
patched-APK cases: four standalone 1.9 layouts, standalone 1.8.1 arm64, three
combined architectures, reversed combined selection and all 15 patches on arm64. Automated checks use local
fixtures and do not qualify real accounts, public relay behavior, carrier/router
NAT traversal, overlay bootstrap, physical-device UI or Android background limits.

See the [discovery guide](PEER-DISCOVERY.md), [testing checklist](SOULSEEK-TESTING.md),
[known limitations](KNOWN_LIMITATIONS.md) and [publishing guide](PUBLISHING-1.1.0-beta.9.md).
