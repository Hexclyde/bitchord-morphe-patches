# beta.17 verification

Test date: 1 November 2026. Target: original BitChord 1.9, Morphe Desktop 1.19.1 / Patcher 2.0.1.
Release MPP SHA-256: `483a23bbfcaccd6780d425b40bbf0ebe4c6f2c6940a6f98a098571321075b3ab`.

## Component and APK verification

- Fixed a runtime failure in anonymous playlist matching by unwrapping boxed Kotlin lookup results before reading page data. Immediate/resumed success and failure cases now have regression coverage.
- **330 Android framework tests passed**, zero failures/errors/skips: installed-feature filtering/counts, gear rules, sliders/defaults/persistence, dependencies and scoped Undo; short-window/font/theme/accessibility behavior; downloads, inline previews, playlists, MicroG chooser and Telegram recovery.
- **31 Python tests**, the complete host regression and **28 native Soulseek tests** passed. Host checks include 147 playback-extras, 135 headphone-correction, 16 per-output EQ, 28 DJ-mode, 28 Audio/Video and 39 patch-control assertions, alongside download/provider/playlist/identity checks. Provider fixtures are component tests, not live account tests.
- **24 exact-MPP APK configurations passed**: all 20 independent selections, complete arm64-v8a/armeabi-v7a/x86_64 bundles and isolated AutoMix. Checks cover DEX control flow, host ABI, capture policy, optional-runtime isolation, native bytes and ten original-speed transition paths. ARM results are APK inspection, not ARM device playback.
- Formatting, **1,489 phrases / 20 languages**, documentation links, release/native/API hashes and metadata passed. Some explanatory strings retain declared English fallbacks.

## Waydroid: Android 13, x86_64

The final APK was installed in place with the existing signing key and app data. Private test instrumentation was kept outside the source/release. Original queue, feature flags, source enablement and test speed were restored; no credentials were entered or exported.

- All **20 installed controls** passed native toggle/Undo checks; configurable gears expanded real settings and Done closed them, while binary rows had no gear. This validates native controls, not every physical audio effect or external service.
- Inspected native icons, category labels, rounded bottom selection and Audio/Video controls. Default and 150%/short-window checks were performed during this pass; theme/reduced-motion/accessibility-role behavior also has component coverage. Full TalkBack and physical-device qualification remain pending.
- Song Download opened Explorer with live results. Tapping a track title selected its card and exposed inline Play/Stop/recording-checks/Now/Later controls without starting a transfer. Source/quality filters and sorting retained explicit admission. The optional recording-checks panel and download-settings panel opened and closed normally.
- Live MP3/FLAC previews decoded and stopped at 30 seconds. The new inline FLAC player paused/resumed, stayed within the result page and retained its buffered transfer through Now. A completed **17,038,839-byte, 16-bit/44.1 kHz stereo FLAC** was independently inspected. One valid test FLAC remains beside the pre-existing MP3; temporary previews and test playlists were cleared. No upload was initiated.
- Downloads local search produced an empty state; Clear restored both files. The duplicate Find sources action is absent. Waydroid's unsupported background blur uses a solid themed fallback.
- Native local playlists passed create/deduplicate, rename/reorder, scoped membership and preference persistence; M3U/CSV/supported JSON parsing, current-queue snapshots and signed-out bookmark add/remove passed. Existing playlists/bookmarks were preserved. Anonymous network matching status: **pass**; complete remote playlist read: **pass**.
- Installed production Streamseek decoding passed **19 audio fixtures plus ABI/cancellation/invalid-input probes: 22 passes**. Fixtures cover FLAC, ALAC, AIFF, CAF, WAV/RF64/Wave64, WavPack, TTA, DSF/DFF, APE, TAK, Shorten, WMA Lossless and MLP, including 96/192 kHz 24-bit and 384 kHz 32-bit PCM. These are actual decoder tests, separate from live peer playback.

## Supplied community add-ons

Each supplied URL was probed as provided, then tested through the native source adapter. The table records manifest/search HTTP status during this pass; services can change.

| Source | HTTP manifest / search | Native result |
|---|---|---|
| 1 · soundfean-musics.onrender.com | 404 / 404 | Native searches returned no playable results; live transition unavailable. |
| 2 · gglos.geisselgottes.cfd | 200 / 200 | Native search, FLAC playback, two-deck overlap and automatic handoff passed at 1.0× and 1.25×. |
| 3 · bluebit.jucepe0929.workers.dev | 200 / 502 | Native searches returned no playable results; live transition unavailable. |
| 4 · eljohnzc-mono.de.deplexo.com | 200 / 500 | Native searches returned no playable results; live transition unavailable. |
| 5 · gglos.ggottes.deno.net | 404 / 404 | Native searches returned no playable results; live transition unavailable. |
| 6 · lastbabe.velixir.run | 410 / 410 | Native searches returned no playable results; live transition unavailable. |
| 7 · eljohnzc-addon.de.deplexo.com | 404 / 404 | Native searches returned no playable results; live transition unavailable. |
| 8 · bitchord-hires-addon.vercel.app | 200 / 200 | HTTP search/stream answered; native playback correctly blocked by its lossless-output requirement. |

GGlos played One More Time → Get Lucky with both decks audible at once, changing fader gains and automatic queue handoff. Tests retained **1.0× and 1.25× speed** and the existing **+3-semitone pitch** on both decks. The extended run completed measurement for 2 selected tracks. The host also reported an analysed mix. Server errors, empty catalogues and the Hi-Res route policy are not counted as passed live AutoMix tests. No lossless-output policy was bypassed.

## Pending and limits

Live Spotify/YouTube Music two-way writes remain **pending**. Fresh Google/MicroG authentication, debrid accounts, public/paired peer transports, external JamesDSP processing, physical ARM phones, Bluetooth/DAC/bit-perfect output and subjective transition quality remain unqualified. Existing Telegram/Soulseek accounts were retained; fresh login/OTP recovery is covered by component tests rather than signing those accounts out.

Streamseek retains its 30-second preview, 256 MiB selected-file limit and transfer bounds. DSD is converted to PCM; the audio route determines output precision/rate. Decoder support is not proof of lossless provenance or bit-perfect output, and compiled DTS/DST/TrueHD variants are not all live-tested. See [preview limits](STREAMSEEK-PREVIEW.md), [patch controls](PATCHES-AND-TWEAKS.md), [AutoMix](AUTOMIX.md) and [earlier verification](VERIFICATION-1.1.0-beta.17-2026-10-31.md).
