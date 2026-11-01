# beta.17 verification

Test date: 31 October 2026. Target: original BitChord 1.9 APKs pinned in PROVENANCE.json, using Morphe Desktop 1.19.1 / Patcher 2.0.1. This build combines the supplied beta.16.4 UI revision and beta.17 features.

## Automated checks

- The complete Android framework suite passed **292 tests**, with no failures, errors or skips. It covers login recovery, MicroG selection, download flows, source analysis, playlists, sync planning and recovery, transition automation and player controls. A real touch-dispatch regression checks that nested switches toggle once and ignore touches while disabled.
- All **31 Python tests** and the full host regression passed. Java, Kotlin and Python formatting, translation catalogs, source links, native/API provenance and release asset checks passed.
- The exact release MPP passed **24 patch-selection configurations**: all 20 individual selections, complete arm64-v8a/armeabi-v7a/x86_64 bundles, and isolated AutoMix. APK checks cover DEX control flow, optimized host ABI, extension isolation, capture policy, native bytes, downloads, playlists and transition hooks. Structural checks do not qualify ARM playback hardware.
- Sync fixtures cover complete reads, account changes, interrupted writes, successful responses without actual remote changes, verified additions before removals, operation limits and recovery without repeating successful additions.

Release MPP SHA-256: `f73339f7dd5d901e57ee75b8b41d083c4d31161152fe0d219f93b768160acc7b`.

## Waydroid checks

The x86_64 app was updated in place on Android 13 without clearing its data. Telegram and Soulseek credentials were retained; Soulseek reconnected using its saved account. Waydroid was restarted once after its session stopped; this was not attributed to an app crash. Runtime checks below span the merged beta.17 iterations; the final MPP was reinstalled for the final UI and settings checks.

- Patch pages use grouped settings rows, contextual descriptions and current values. Related actions share a row. Playlist tools and Sync use the native typography and surfaces; the empty space below short pages retains the page background. Playlist tools remained readable at 150% text size.
- A real tap on a nested debrid switch opened the key prompt. Cancelling retained the disabled provider state. No debrid key was entered. The shared capsule has enough row width to draw without clipping.
- Downloads search opens as a compact rounded field with one close control. An unmatched query produces the empty state; Close clears the query and restores the file. Back first dismisses the keyboard, then closes search.
- All five native navigation tabs were captured at 100%, 130% and 150% text size. Selected capsules remain rounded; text stays inside their bounds. Downloads fits at the default size and truncates at the largest size.
- The Audio/Video selector was visible on a YouTube track and blended into the player background. Non-video source visibility also has framework and APK coverage.
- The transition editor uses grouped timeline, timing, envelope and effects controls, white sliders and separate Preview/Save actions. Portrait, larger text and landscape were inspected; the landscape timeline shows both lanes. Numeric editing uses the translucent modal treatment.
- Two generated local FLAC tracks played through the real player. Saved eight- and six-second transitions previewed from the outgoing tail into the next track and restored the original paused track/position. Restoration differences were approximately 0.03 and 0.21 seconds under the test load. The existing pitch setting remained audible on both tones; captured output showed outgoing level falling while incoming level rose. This is controlled synthetic audio evidence, not qualification of every provider or recording.
- The signed-out Library entry and Save queue flow created a device copy of the current and upcoming queue. An M3U selected through Android's file picker reached review and saved a one-song device playlist. Returning to Playlist tools refreshed the saved list; reopening confirmed persistence.

Temporary QA playlists and generated audio files were removed. The original 11-track queue was restored and paused. AutoMix and debrid were restored to their prior disabled settings; text size was restored to normal.

## Live-test limits

Live Spotify/YouTube Music sync is **pending**, as requested for this package. OAuth connections and real external playlist writes were not exercised. Manual two-way membership sync is implemented; scheduling, playlist-name/order sync and other service connectors are not included.

Community endpoint probes returned two successful searches for the test query and one stream resolution. Other probes returned HTTP 404, 410, 429 or timed out. These results do not establish playback, download completion, master provenance or AutoMix operation across all supplied sources. An earlier live Soulseek MP3 download decoded successfully; two FLAC peer attempts failed. Debrid accounts, public-peer transports and every possible addon/quality combination remain unqualified.

No new physical-phone, Bluetooth/DAC, hardware-decoder or subjective audio qualification is claimed. New playlist-tool and sync phrases fall back to English where translations have not been supplied. See [playlist tools](PLAYLIST-TOOLS.md), [AutoMix](AUTOMIX.md) and [transition editor](TRANSITION-EDITOR.md).
