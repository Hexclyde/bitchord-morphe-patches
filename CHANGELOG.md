## 1.1.0-beta.19

- Support the BitChord 1.9.1 universal APK for shared audio, Telegram and Soulseek. Unsupported 32-bit x86 libraries are removed; ARM64, ARMv7 and x86-64 remain available.

- **Unofficial Telegram** catches up a delayed quality upgrade before fading it in. If the copies cannot be aligned, the current audio keeps playing.

- Fix a startup crash on BitChord 1.9.1 with **Unofficial Telegram** or **Soulseek** installed.

- **Automix Editor** keeps the waveform layout steady through a continuous swipe, including swipes that reach the start of the settings. A fresh pull at the start expands the waveform.
- **Diagnostics** records AutoMix failure stages, memory waits, preview loading times, buffering, source errors and why a preview stopped.

- **Automix Editor** reads downloaded files directly for track analysis and waveform loading.
- **Audio and video switch** refreshes lyrics when the active recording’s length arrives late and preserves a chosen provider across a version with no result.

- **Automix Editor** analyzes queued tracks with missing durations and uses the selected file’s duration when its queue label is rounded.

- **Karaoke** uses smaller model working buffers. Read-ahead stays bounded while the model is loaded, and background decoding waits when heap headroom is low.

- **Automix Editor** reuses native analysis, timing and beat matching unless an explicit tempo override is saved. Deck headers show measured keys and tempo with low-confidence indicators, and Preview waits for an actual automatic plan.

- **Automix Editor** follows the playing pair without discarding drafts, prioritises the outgoing waveform and retries failed waveform requests. Queue analysis prepares one additional readable local or fully cached track when current and next analysis have settled.

- Playlist and album downloads expand source results inside each track card, with reported format filters, duration, size and selected-source details. Each original duration stays visible beside the selected copy’s reported duration.
- Tapping a completed download opens BitChord’s local player. Its recorded source stays available for local playback, and FLAC tag updates preserve existing metadata when no replacement is supplied.

- **Karaoke** (formerly Sing) adds a microphone control directly above Translate in the lyrics. Tapping it switches on separation-model vocal removal and opens a vertical slider linked to the settings slider; after it closes, the microphone stays faintly visible and can be tapped to reopen the slider.
- **Diagnostics** records app and patch events for startup and sign-in troubleshooting. Start and stop capture explicitly, then share a compressed report through Android’s share menu.
- **Audio and video switch** carries a manual lyrics-provider choice between versions of the same queued song and reloads lyrics for the active version.
- Returning to audio clears the video-origin flag so native AutoMix can analyse and blend the song again. Fullscreen can follow the player's request when the window ignores rotation, with Back handled in the player's scope.
- The player's **Download** control uses the current downloadable non-YouTube stream first, then matching alternatives ranked by quality. YouTube is the last fallback when enabled; the control shows the download queue's reported percentage.
- **Transition editor** restores the full waveform when scrolling back to the top, offers an overlap slider and separates draft saving from applying a transition.
- **Automix Editor** has uniform controls, separate track-tempo and matching controls, and adjustable filter depth. Explicit overlap rates keep pitch and return to the listener’s speed after the blend.
- Track analysis accounts separately for decoding and measurement memory, and offers Retry when repeated memory rejections prevent analysis.
- **Automix Editor** loads device audio and reverses preview order without changing listening. **Use this pair** explicitly changes the current and next queue entries; opening a draft does not apply it.
- Draft changes in **Automix Editor** survive rotation without an older delayed save overwriting them.
- Fixed: Google sign-in handles links to other apps without opening an unknown-scheme error page.
- **YouTube Music** can be turned off from Sources. Playback only stops YouTube audio and Full disable also stops its search and browsing; songs use saved files, matching local music or another enabled source, with **No audio track found** shown when none has playable audio.
- **DJ mode** orders upcoming tracks by key and tempo with Steady, Build up and Wind down choices. Compatible keys receive longer blends; Save DJ order updates a local playlist after confirmation.
- **Unofficial Telegram music** updates the saved index when audio and ZIP posts arrive, change or disappear while connected.
- **DJ mode** leaves queue order to native shuffle while shuffle is on. **Audio and video switch** respects music-only playback and manual version changes.
- **Battery saver** stands down for native AutoMix even without **Automix Editor** installed. **Equaliser per output** leaves the native equaliser off while JamesDSP controls effects.
- Supports BitChord **1.9.1** and its experimental **1.9.1-beta1** build alongside 1.9.
- The player's **Audio/Video** pair is centred above the playback-origin caption. **Automix Editor** opens from a separate waveform icon below the stats; the icons follow BitChord's player colours and blending.
- **Automix Editor** is the patch name for source-aware transition analysis and pair editing. Its preset options remain scrollable, with compact Save draft and Apply controls.
- **Downloads** offers a default audio folder through Android’s picker, including writable folders on SD cards and USB drives. New transfers keep their selected destination through pause and retry.
- Completed files in selected folders use Android’s document-provider delete operation. A failed deletion keeps the saved entry.
- YouTube video preparation uses less memory when handling cipher scripts.

## 1.1.0-beta.18

- **Transition editor** gives sliders and numeric entry controls larger touch targets. Screen readers announce adjustment values with their units.
- **Audio and video switch** carries a manual lyrics-provider choice between versions of the same queued song. Lyrics load again for the active version.
- **Patches & tweaks** has a sliders icon, clearer feature descriptions and a searchable shortcut to Storage Sense.
- **Stats** shows the average bitrate of the prepared rendition and its actual source. Old or ambiguous cache entries show an unknown source.
- **Play the best source first** prepares upcoming songs through enabled sources with recording and timing checks. Separate queue entries can use different copies without changing an entry already playing.
- Complete verified replacements reclaim obsolete YouTube playback cache while protecting active readers and saved **downloads**.
- **Unofficial Telegram music** handles cached and bounded recording reads without crashing during two-player quality upgrades. Local message-cache eviction does not invalidate an unchanged remote file.
- **Source-independent AutoMix** measures the selected recording with bounded DSP when full analysis cannot fit, including when Steady playback is off or not installed. Low-confidence analysis uses a basic crossfade without changing playback speed or pitch.
- **Play alongside other apps** (new, off by default) continues music over other apps and pauses for calls, with recovery for the same track.
- **Steady playback** (new, off by default) offers connection-specific read-ahead and offline retries, with combined music and artwork buffers bounded on smaller heaps.
- **Smart downloads** (new, off by default) queues frequently played songs through enabled download sources in the selected quality.
- **Sing** offers local separation-model vocal removal with a filter fallback and offline Spleeter conversion.
- **Storage Sense** lists audio caches, protects active resources and offers confirmed file deletion and content-hash duplicate review. Cleanup rules start disabled.
- Downloads settings contain patch controls alongside BitChord’s quality, Wi-Fi, export and playlist-sync options. Selected-source filenames and converted formats stay consistent across patch selections.
- **Smart playlists** creates local library matches by default, with a separate optional text-service connection and review before saving.
- **Peer downloads** separates Receive, Share and Transfers, with pairing instructions, stable progress rows and connection details behind an explicit action.
- **FLAC Downloader** is unavailable in the patch catalogue. Existing saved files remain accessible from Downloads.

## 1.1.0-beta.17

- Update **FLAC Downloader** to provider browser verification and current FLAC/MP3 320/MP3 128 preparation; reject format downgrades.
- Add exact-source Pause, Resume and Retry, separate history removal and confirmed device-file deletion. Route Library **Downloads** to the patched hub.
- Show peer-reported **Soulseek** file/audio/queue metadata in native details panels. Hide the **Audio/Video** row in Lyrics and Queue; inset the transition editor below system bars and the keyboard.

- Unwrap boxed Kotlin results from native suspending lookups before reading playlist/search pages. Handle immediate and resumed success/failure results consistently.

- Integrate Soulseek previews into result cards with explicit playback, elapsed time, actual decoded format and transfer reuse. Keep local-reference and recording-evidence tools in an optional compact native panel.
- Group Unofficial **Telegram** tasks into native account/music cards and expandable advanced settings. Keep application credentials behind the setup control while preserving login recovery, indexing, storage and upload flows.

- Add native installed-feature search, All controls / Enabled selection, category filters and accurate counts to **Patches & tweaks**. Build settings lazily through explicit gears, with Done, fine adjustment, Reset and preference-scoped Undo.
- Keep source-gate writes off the UI thread and avoid migrating download opt-ins while reading the page. Respect Streamseek and **JamesDSP** dependencies without restoring cancelled transfers or credentials.
- Refine Downloads with live filter counts, an Attention filter, grouped sections and prominent known transfer percentages. Align Explorer filters and selected-source actions with the native settings styling.
- Defer native **AutoMix** model-session release until in-flight inference finishes, including cancellation and service shutdown; keep the UI responsive during cleanup.

- Add source-independent analysis through selected playback adapters and complete rendition caches, including picked add-on rows.
- Pin readers, isolate analysis persistence by rendition, cancel stale/service-ended jobs, and preserve background source diagnostics.
- Prefer enabled alternatives to YouTube and verify automatic replacement recordings across three fingerprint/timing windows.
- Preserve the listener's playback speed and pitch throughout AutoMix; use cue/fade/filter transitions without tempo-ramping loop mixes.
- Make **Battery saver** audio offload yield to AutoMix.
- Use a compact Downloads search field with one close control; closing clears its filter and dismisses the keyboard. Back closes search after dismissing the keyboard.
- Inherit the host background on embedded download pages while keeping standalone page backgrounds. See docs/AUTOMIX.md for analysis limits.

- Add a native transition timeline with directed-pair timing, volume curves, three-band EQ, filter sweeps, preview/restore and saved edits.
- Add signed-out YouTube playlist bookmarks, complete device copies, queue snapshots and local editing through **Playlist tools** in Library.
- Add native portable playlist-file import, anonymous track matching, candidate review and confirmation before saving.
- Add reviewed two-way Spotify/YouTube Music playlist membership sync, encrypted PKCE credentials, complete-read checks, operation limits and recovery after partial writes. Sync is manual and covers playlist membership.
- Blend Audio/Video controls with the player and keep five-tab selected highlights clear of label text.
- Replace navigation-button stacks on patch pages with grouped setting rows, descriptions and current values. Pair related actions in debrid, peer discovery, headphone and JamesDSP settings; preserve native capsule switches and primary actions.
- Accept Android playlist MIME types in the import picker and refresh device playlists when returning from import.
- Fix nested download switches ignoring touch and clipping their native capsule at the edge of the row.

## 1.1.0-beta.16.4

- Use **Download Explorer** with pill filters, right-aligned radios, and Now/Later actions inside only the selected card. Hide the availability filter when no result reports readiness; unknown readiness stays under All.
- Fix device playlist creation, membership and restore against BitChord 1.9’s optimized concrete StateFlow implementation, whose MutableStateFlow interface no longer declares the reflected methods. Preserve existing playlists and source URIs.
- Replace the window-level **Audio/Video** overlay with measured AndroidView children in the actual portrait player Column and landscape main pane. Reserve row height before the artwork, inherit the player's insets, movement and stacking, and remove fixed window offsets, elevation chasing and animation-window hooks.
- Use headphone and video icons with separate 52 × 48 dp touch targets, tooltips, accessible labels and checked states. Keep the saved YouTube preference across tracks and restarts.
- Release the row's height when the patch is disabled or a local/add-on track plays. Preserve the row during player gestures so artwork does not jump while dragging.

## 1.1.0-beta.16.3

- Fill the player content slot when embedding **Downloads** and the song source picker, including when the optimized host passes a null modifier. Prevent the source picker from collapsing into a narrow column.
- Search the displayed downloads locally by title, artist, source and format. Keep source discovery under Find available downloads; preserve query filtering across view changes and progress updates.

- Keep the **Audio/Video** player pill above the elevated Compose player, so it is painted and receives taps.
- Dismiss the full player when opening source selection; do not leave Explorer behind its song menu.
- Replace recursive rewrapping of the Downloads navigation callback with an idempotent binding.
- Rebuild Explorer with track context, selectable source cards, reported lossless/availability filters and download/queue actions. Preserve asynchronous searches, explicit file selection and batch review.
- Use the host green capsule in nested audio-format settings while preserving native file-selection checkboxes.
- Send **Soulseek**’s progress link to Downloads, and exclude failed, cancelled and paused jobs from the active count.

## 1.1.0-beta.16.2

- Expand Streamseek's optional on-device decoder to lossless PCM, ALAC, APE, WavPack, TTA/TAK, WMA Lossless and DSD files; preserve saved format choices and explicit preview controls.
- Use distinct native vector icons for every installed tweak, including sleep fade, **battery saver**, **DJ mode**, **AutoMix** and the player **audio/video** switch.

- Keep visible MicroG account selection in a native app-themed dialog with explicit title/account text colors, 56dp rows, wrapping and a Google sign-in fallback. Request a login token only for the chosen account. Preserve Android’s account-add/visibility-consent flow when none are visible.

## 1.1.0-beta.16.1

- Allow correcting the login phone number while waiting for a **Telegram** code, without deleting the session or application configuration.
- Offer code resending only when Telegram provides a delivery method and its cooldown has elapsed. Show the remaining wait and keep typed codes intact during countdown updates.

## 1.1.0-beta.16

- Rebuild the production Android **Downloads** views with compact controls, real thumbnail/embedded artwork, source/format labels, separate file status, actual thin transfer progress and five saved layouts. Keep management controls under the settings gear, deduplicate saved host aliases, remove the fake public-sharing download row and constrain the back button.
- Replace the duplicate host header only while Downloads owns the content. Preserve the native player and bottom navigation; Downloads remains conditional on the installed add-on.
- Bind the **Audio/Video** text pill to NowPlayingScreen's actual composition view, independent of the animation window. Cover reduced motion, stale background windows and player-content replacement. Preserve the persistent YouTube preference.
- Show **Telegram**'s opening page before native bridge preparation. Run saved-index counts on a separate background worker, cache the visible status and suppress duplicate page launches.

## 1.1.0-beta.15

- Keep **Downloads** inside LibraryScreen's balanced Compose restart scope and request AndroidView's default modifier when the optimized host passes null.
- Restore the **Audio/Video** control after every player-window open, close and recreation, including inlined host field writes.

## 1.1.0-beta.14

- Add optional **Streamseek previews**, depending on the **Soulseek** download patch; the in-app capsule toggle starts off and has no gear. Preview one selected MP3/FLAC peer file for up to 30 seconds in a separate Media3 player.
- Share the native temporary transfer with an explicitly selected immediate or queued download. Expiring capabilities, session checks and private activity keep ordinary Soulseek playback download-only.
- Compare a candidate excerpt with a user-chosen local reference using independent PCM decoding and landmark alignment. Show checked duration, offset and counts; export JSON evidence.
- Inspect FLAC header fields and compute SHA-256 only after the complete file arrives. User confirmation binds to that checksum and does not certify the master or lossless origin.

## 1.1.0-beta.13

- Add an optional, independently written **Flac Downloader Audio** adapter for the service protocol used by aztecempire's Spotube plugin. Its native Sources toggle starts disabled; enabled results join Addons search, **Downloads** explorer and batch selection. Requested FLAC/MP3 choices remain explicit and unverified, with bounded preparation polling and no silent format substitution.
- Add optional **Mixed-source playlists** using BitChord's existing on-device Library shelf, playlist pages and player. Preserve file/download URIs and addon source identities across restarts; support membership changes and native rename/delete. Keep the original YouTube Music and offline playlist destinations.
- Binary features show help without a settings gear.

## 1.1.0-beta.12

- Show settings gears only for features with configurable controls; binary toggles keep contextual help without a settings button.

- Add persistent Grid, List, Compact, Comfy and Detailed download views.
- Remember **Audio/Video** as a YouTube playback preference across tracks and restarts; unavailable versions and non-YouTube sources do not change it.

- Audio and video switch starts off; enable it under **Patches & tweaks**.
- Use BitChord 1.9 green switches across patch settings and download controls. Keep the **Downloads** navigation icon exclusive to installs with the download addon.

## 1.1.0-beta.11

- Embed **Downloads**, Explorer and batch source selection in BitChord 1.9's real player content slot, with measured bar padding, a selected Downloads tab and Compose back handling.
- Add album/playlist/artist batch review for up to 200 YouTube catalogue tracks. Choose each source/quality or search for unique artist/title matches in an enabled addon or **Soulseek**; unresolved tracks stay unselected.
- Keep partial submission receipts and exact Soulseek file metadata across later searches; allow 256 waiting selected files without interrupting active work.
- Expose native public Soulseek browse/search/upload support through an explicit selected-file inventory, 1–4 upload slots, live upload progress and cancellation. Inventory changes withdraw outstanding grants. Public sharing pauses after a process restart.
- Archive existing downloaded files to the private **Telegram** **main channel**. Verify an independent remote read-back before enabling explicit local removal; recheck the receipt and original hash at removal. Restore original bytes through the existing verified phone publisher.
- Keep sharing and archive operations under the foreground download notification and its Stop/Android time-limit handling.
- New UI phrases have English fallbacks in the 20-language catalogue.

## 1.1.0-beta.10

- Adds a host-native **Downloads** icon in both bottom-bar styles on BitChord 1.9.
- Opens **Download Explorer** from eligible manual single-track downloads, with live
  enabled-addon searches, reported row quality and exact **Soulseek** file choices.
- Adds next-slot and FIFO admission, preserving active transfers and remote queues.
- Persists selected-addon source bindings and blocks automatic provider fallback.
- Adds a Downloads hub with existing host states, Soulseek progress, confirmed
  phone/Telegram copies and existing paired-peer shares.
- Preserves the original automatic source path as a separate confirmed action.

## 1.1.0-beta.9

- Add guided **Soulseek** setup, dedicated search and named download progress under
  **Patches & tweaks**. Eligible manual single-track **downloads** offer Soulseek search
  or the original flow explicitly. Cancelled/failed searches do not silently
  download YouTube, and saving a different selected file does not mark the
  original YouTube row as saved. Soulseek remains download-only.
- Return a settled initial Soulseek shortlist once the highest enabled format
  arrives, while keeping the full search budget when it does not. Repeated
  queries can reuse a small, short-lived cache bound to the login and format policy.
- Add opt-in compatible-peer discovery under Patches & tweaks → Peer downloads
  → Connection setup. Explicit scans use signed public Nostr profiles; publishing
  your own profile is a separate switch, off by default.
- Exchange bounded batches of signed public profiles with already paired clients
  after capability negotiation. Discovery does not pair a key, share files, expose
  private grants or join ordinary Soulseek or BitTorrent swarms.
- Translate the discovery page and new common states across all 20 supported
  languages. Keep screenshots and screen recording enabled.

## 1.1.0-beta.8

- Updated all patch hooks for BitChord 1.9 for the **Soulseek**, debrid and peer-transfer tools under **Patches & tweaks**.
- Added download-page translations across all 20 supported languages,
  including common transfer states, notifications and right-to-left layout.
- Enabled screenshots and screen recording, including
  setup pages and dialogs.
- Adapted **Telegram** **downloads**, playlists, smooth upgrades and **Automix** audio
  processing to the changed 1.9 interfaces and added bit-perfect behavior.
- Nest provider settings, with one expanded service at a time;
  disabled services hide their controls and disable their settings gear.

## 1.1.0-beta.7

- **Per-download quality** (new, off by default) asks for Standard, High or Lossless quality per download, or once for a whole album.
- **Headphone correction** (new, off by default) applies measured AutoEq or pasted parametric profiles per output, with adjustable strength.
- **Equaliser per output** (new, off by default) remembers BitChord’s equaliser for each output and shares settings as codes.
- **DJ mode** (new, off by default) chooses a neighboring key and close tempo using playback measurements and available AutoMix analysis.
- **JamesDSP** (new, off by default) opens the effects app, announces BitChord’s session and transfers effect handling.
- **Sleep timer fade-out** reworked: the fade length is yours to set (3 to 60 seconds), the timer's After this song option fades the last seconds of that song instead of stopping it at full volume, and a fade stands down the moment you play, pause, seek, change the song or set the timer again, with the volume put back.
- **Battery saver** reworked: the page shows which formats the phone's audio chip can play and which decoder is in use. The chip isn't asked for while **Sing**, Headphone correction, DJ mode or JamesDSP needs the decoded audio, and a changed speed or pitch is only handed over when the chip supports it, so those keep working.
- Lossless addons over Bluetooth reworked: allow them for every Bluetooth output or only for the devices you pick from those you've connected. The page shows the output in use and its codec, and says when BitChord already counts it as lossless or can't read the codec.
- Six more languages: Polish, Hebrew, Italian, Thai, Vietnamese and Traditional Chinese, following BitChord's language setting.
- The **Telegram** audio cache is trimmed at most every five minutes as songs start, instead of at every start. Applying a new cache size still trims at once.
- Every feature row on **Patches & tweaks** has its own switch, and the new features start switched off. Lossless addons over Bluetooth has a switch of its own now, then all outputs or only the ones you allow.
- In Polish, Hebrew, Italian, Thai, Vietnamese and Traditional Chinese, the **Soulseek**, peer and debrid download labels from 1.1.0-beta.6 are still in English.
- The BitChord compatibility check says when BitChord's own build of a new version fails, instead of reporting a hook that no longer matches, and tries that build again on the next run.

## 1.1.0-beta.6

- Renamed Tweaks & patches to **Patches & tweaks** and moved download
  controls there. **Soulseek** still registers internally for manual Addons search
  and **downloads**, but its setup row no longer appears in Sources. Unofficial
  **Telegram** keeps its existing settings, source row and behavior.
- Added compact feature rows with enable switches and BitChord's settings gear.
  Off hides the detailed controls and disables the gear. Enabling expands one
  feature; opening another collapses the previous details without disabling it.
  Saved settings persist when a feature is switched off.
- Added explicit enable gates for playback tweaks and download tools, so a
  disabled feature does not keep applying its effect or starting new work.
- Restyled download setup and management pages to follow BitChord's fonts,
  theme, rounded groups, back navigation and controls.
- Replaced the download format chooser's Up/Down buttons with numbered rows,
  switches and drag handles. Accessible move actions provide an alternative to
  dragging; at least one format must remain enabled.

## 1.1.0-beta.5

- Added **Unofficial Soulseek audio**, a download-only Morphe patch for BitChord
  1.8.1. Manual Addons **downloads** stay bound to the exact selected peer and login
  session, before the host can reuse an unrelated file by filename.
- Added first setup for Phone, **Telegram** **main channel** or Both, followed
  by enabled formats and their order. Completed audio is inspected before saving;
  filenames do not prove its recording or original lossless provenance.
- Added optional Real-Debrid, AllDebrid, Premiumize and TorBox
  downloads with independent switches, all off by default, and protected personal
  API keys. Resolve a supported HTTPS link or magnet, select audio entries and
  save them through the same Phone/Telegram/Both publisher. Provider-side cloud
  requests can remain after local cancellation; the app does not automatically
  switch paid providers or use them as a music search catalogue.
- Added explicit paired-phone file sharing with signed encrypted Nostr
  offer/request/grant authorization. App keys are separate from Telegram accounts.
- Added same-socket public STUN discovery and UDP punching with
  certificate-pinned QUIC as the primary paired-download path. Embedded I2P,
  Tor onion and paired IPFS Bitswap runtimes provide eligible fallback routes.
- Added durable per-item journals, verified chunk recovery, pause/cancel/resume,
  unchanged-byte phone publication and confirmed original-document Telegram
  uploads. Both destinations preserve separate status and captured settings.
- Added on-phone relay benchmarking with a bundled validated candidate pool,
  bounded checks and no GPS/GeoIP requests.
- Serve Soulseek audio through completed local downloads, with no automatic live replacement.

## 1.1.0-beta.4

- Fixed **Telegram** playback falling back to YouTube after cache cleanup removed the active file. Cleanup protects recently downloaded files, and missing portions are requested again.

## 1.1.0-beta.3

- Tweaks & patches: the settings of the playback patches moved from the end of the Equalizer page to their own Tweaks & patches group near the top of Settings.
- Patch pages follow BitChord's own language: the language picked in BitChord's settings is used, not just the phone's.
- Fixed **Telegram** songs failing partway and falling back to YouTube ("open failed: ENOENT" for a file in `temp`, reported with "Faint"). TDLib moves a file out of its temp folder when its download completes, and deletes cached files to free space; when that happened between finding the file and opening it, playback failed and BitChord gave up on the Telegram copy for that song. The patch now asks TDLib where the file is again, downloading the part again if it was deleted. The same failure made background file checks skip files ("couldn't look into …").
- Fixed "Invalid request" after signing in with MicroG: MicroG RE's one-time sign-in link was opened next to the cookies Google's sign-in page had left, and Google turned it down. Google's and YouTube's cookies in the sign-in page are now cleared first, as MicroG RE does in its own pages. Other sites' cookies stay. The log shows where MicroG RE's link goes, without the link's secret parts.

## 1.1.0-beta.2

- Fixed a cover playing instead of the song: a **Telegram** file with the song's title, artist and length was trusted without being listened to, so a cover tagged as the original could replace it, as happened with "Enter Sandman". Before switching, the patch now always compares the Telegram copy with what's playing. When the song has only just started, it listens for a few seconds first. If the two don't sound alike, the stream keeps playing; a file that sounds different at two switches isn't used for that song again.
- The playback features are now six separate patches, all off by default: **Sing**, **Pitch control**, **Loudness level**, **Sleep timer fade-out**, **Battery saver** and Lossless addons over Bluetooth. Turn on only the ones you want in Morphe. The Playback extras page shows the settings of the ones you added.
- Fixed Sign in with MicroG not being offered on Android 11 and newer: Android hides other apps' account types from an app unless it declares them, so BitChord couldn't tell MicroG RE was installed and opened Google's sign-in page. It's now declared. When the offer is skipped, the log says why.
- Install as Bitchord+ now says what went wrong when a patch from another source has already changed BitChord's package name.

## 1.1.0-beta.1

- Playback extras: a new patch, on by default, with a Playback extras page at the end of Settings → Equalizer. It works with or without the **Telegram** patch, and nothing changes until you change a setting there.
  - **Sing** turns the lead vocal down so you can sing over the song. It's ported from the vocal remover in [Auris](https://github.com/OreoMuncher45/Auris) by OreoMuncher45: by default only the voice range is cut, only while someone is singing, and drum hits ease it off. Whole centre uses Auris's first method, which removes everything in the middle.
  - Pitch moves the key up or down by up to 12 semitones without changing the speed, and BitChord's speed control keeps it.
  - **Loudness level** sets how loud loudness normalisation plays songs, from -23 to -8 LUFS instead of BitChord's fixed -14.
  - Fade out at the sleep timer: the song fades out over 10 seconds before the timer pauses it, instead of stopping mid-phrase.
  - **Battery saver** lets the phone's audio chip decode songs. BitChord's effects don't run on songs played this way, so it's off by default.
  - Lossless addons over Bluetooth keeps addons marked lossless in use over Bluetooth, where BitChord normally skips them.
- Credits for BitChord, Auris, Prism and Stash are in the README and THIRD_PARTY_NOTICES.

## 1.0.21

- Sign in with MicroG: a new optional patch. With [MicroG RE](https://github.com/MorpheApp/MicroG-RE) installed, signing in to YouTube Music asks whether to use an account that's already in MicroG, instead of typing it into Google's sign-in page. You pick the account, allow BitChord once in MicroG RE, and BitChord carries on to its usual channel choice. Without MicroG RE nothing changes. If MicroG can't sign you in, Google's page opens.
- Fixed zips not being opened after signing in again: **Telegram** numbers files afresh after you sign in again or restore a backup, and every zip still waiting to be opened failed with "Telegram 404" and was given up on. The patch now asks Telegram for the post again and uses the file's new number. Zips that were given up on this way are opened again once, and the background fake lossless check recovers the same way.
- Fixed "YouTube" shown for a FLAC under **Stats** for nerds: a Telegram copy that BitChord plays again from its song cache keeps the song's YouTube source. YouTube never sends lossless or Dolby audio, so it now shows as Unofficial Telegram (saved copy).

## 1.0.20

- Fixed a crash when a song starts playing (1.0.18 and 1.0.19). The patch's audio sink hook called code that Android doesn't let BitChord's player reach, so playback stopped with an error.

## 1.0.19

- The switch to **Telegram** is now lined up by the waveform: after the loudness curves find the offset to a few milliseconds, the two waveforms below 3 kHz are compared within that, which finds it to a fraction of a millisecond, even when the stream and the file have different sample rates.
- No more small repeat at the crossfade: the Telegram copy took a moment to start after BitChord moved it to the stream's position, so it began a little behind, often by tens of milliseconds. Before the crossfade, while it's still silent, the patch now compares what the two players are playing at that moment and has the Telegram copy leave out exactly the audio it's behind by. Its player is told its position with that audio counted, so lyrics and the progress bar stay on the song. When it's more than 150 ms off, or ahead, it's moved again first. BitChord's wait before the crossfade can now take up to 2.6 seconds for this; the stream keeps playing meanwhile.
- Fixed the offset being measured as if at 1x when a playback speed is set.

## 1.0.18

- No more repeat when a song switches to **Telegram**: BitChord starts the new copy at the same position as the YouTube stream, but two uploads rarely start at the same instant, so the switch repeated or skipped a little. The patch now compares the last seven seconds heard with the same part of the Telegram file, finds the offset to within a few milliseconds, and starts the Telegram copy that much earlier or later.
- Fixed hi-res files showing "5 kbps": BitChord reads a bitrate above 3,000 as bits a second, so a 24-bit 192 kHz FLAC's 5,000 kbps showed as 5 kbps. The bitrate is now sent in bits a second.

## 1.0.17

- **Back up and sign out**: Session → Sign out now offers to back up first. The backup holds your music chats, settings, corrected names, **main channel** setup and the **saved index**, with its fake lossless checks and names found by sound. You're signed out only once it's saved. After you sign in again, a Welcome back card restores it all in one tap, without indexing your chats again. Start fresh forgets it instead, and the file stays where you saved it.
- Your AcoustID key stays on the phone when you sign out. It's still never in backups.

## 1.0.16

- Fake lossless check: with Check lossless files on (under Track details, on by default), a FLAC or WAV file that plays is checked a minute later. A file made from an MP3 or AAC keeps that encoder's cut-off, and the check finds it in a few stretches of the file, much of which playing has already downloaded. What it finds:
  - made from a lossy file: the legend shows FLAC 16bit · from ≈128 kbps, and the file is ranked as that MP3 by BitChord, by Hide duplicate songs and by Lossless only copying;
  - a hint of a lossy source that isn't conclusive: · maybe lossy, with nothing else changed;
  - hi-res made from a CD-quality master: FLAC 24bit · from 44.1 kHz, ranked as CD quality;
  - a 24-bit file holding 16 bits: FLAC 24bit · really 16bit;
  - a lossy encoder named in the file's tags, such as LAME.
- **Check in the background** (off by default) goes through every lossless file in your music chats too, up to 1 GB a day, keeping to the **saved index**'s Wi-Fi and charging switches. What's found is kept in the saved index, survives its monthly rebuild, and goes with it when you share it.
- Name untagged files by sound (under **Track names**, off by default): files without tags are looked up in AcoustID, the free database run by the MusicBrainz community, with your own free AcoustID key. A fingerprint of the file's first two minutes is sent, not the audio. A file that's found gets its real title and artist, so search and BitChord's matching find it by them.

## 1.0.15

- Community channels lists only channels, groups and bots. Chats with a person no longer show up there. If you picked one before, open Community channels and press Save to drop it.

## 1.0.14

- Basic and Advanced tabs on the **Telegram** page. Basic has your API details, your account, your music chats, the **saved index** and **track names**; Advanced has the **main channel**, track details, playback, storage, and backup and sharing.
- Chats in folders and the archive: Music chats → Choose first asks where to pick from, All chats, one of your Telegram folders, or Archived chats, and lists every chat there, not just the first 100. The main channel and community channel pickers include archived chats too.
- Albums with a cue sheet: an album kept as one FLAC or WAV file becomes one song per track, named from its cue sheet: a `.cue` file posted in the same chat, a `.cue` in the same zip, or the FLAC file's own. Each track plays exactly its part of the file, decoded on the phone and handed to BitChord losslessly.
- Albums in Playlists and **downloads**: a chat's albums (songs Telegram posted together, zips and cue sheet albums), each to save as a playlist or download.
- Backup and restore: settings, music chats, corrected names and the main channel setup, and the saved index if you like, to a file you choose. API details and the Telegram session are never in it.
- Sharing an index: post a chat's saved index in Telegram (Saved Messages, your main channel, or the chat itself if you can post there) or save it as a file. With Use shared indexes on, a chat with an index file there is indexed from it in a minute, then topped up from Telegram. Each song from a shared index is checked against its post when it plays.
- Newest first under Main channel: copying starts with each community channel's newest post while it's still being indexed.
- Index only on Wi-Fi and Index only while charging under Saved index.
- The saved index goes faster while Telegram answers without asking for a pause, up to about 8,000 files a minute, and slows down when it asks.
- TrueHD Atmos is recognised in .mka files.
- Chats indexed by an earlier version are indexed again once, in the background, to find cue sheets and albums; their current index keeps answering meanwhile.

## 1.0.13

- Dolby audio: Dolby Digital (.ac3), Dolby Digital Plus (.ec3, .eac3) and Dolby files in .m4a, .mp4 and .mka are found and played like any other music file. The patch reads the start of each file to tell what's inside:
  - the **format legend** shows Dolby Atmos, DD+ 5.1, DD 2.0, TrueHD 7.1 or AC-4;
  - a Dolby Atmos file is passed to BitChord as Atmos, so it gets BitChord's own Dolby Atmos badge and ranking;
  - Dolby Digital Plus in an .m4a is no longer mistaken for ALAC from its bitrate, and Dolby TrueHD counts as lossless;
  - ALAC in an .m4a now shows its real bit depth, such as ALAC 24bit.
- BitChord's own decoder plays Dolby Digital, Dolby Digital Plus and Dolby TrueHD on any phone; Dolby Atmos plays as 5.1 or 7.1, or with its height sound where the phone or speaker supports it. Hide Dolby files this phone can't play (under Track details, on by default) hides Dolby AC-4 files on phones without an AC-4 decoder.
- The **Telegram** page in 14 languages: Spanish, Portuguese, French, German, Russian, Turkish, Arabic, Urdu, Hindi, Bengali, Chinese (Simplified), Japanese, Korean and Indonesian. It follows the phone's language, and Arabic and Urdu read right to left. Anything not translated shows in English.
- Fixed Copy into **main channel** waiting for hours with nothing to show. The **saved index** went through one chat at a time, so the community channels and the main channel waited behind any large music chat, and copying waits for a channel to be indexed. Now the main channel is indexed first and the other chats take turns a page at a time. Copying starts from each community channel as soon as it's indexed, and the status line says which channels it's still waiting for and how far they've got.
- A copy Telegram refuses is tried again three times, ten minutes apart, with the reason on the status line, instead of being skipped straight away. Refused copies are counted, and a song is only counted as in your main channel once it's really been copied.
- Community channels no longer lists private channels you own.
- Your main channel is indexed as soon as you choose it, not only while copying is on.

## 1.0.12

- Hide duplicate songs (under Track details, on by default): search results show each song once, as its best copy. Copies with the same title and artist and lengths within two seconds count as the same song; lossless beats lossy, then the higher bit depth, sample rate and bitrate win.
- Copy into **main channel** counts audio files: "Copied 2,140 audio files in 1,310 posts", counting each song in a zip.
- A zip is copied when any of its songs is new, and its songs are judged together even in a large zip. Songs that go over inside a copied zip no longer count as skipped duplicates.

## 1.0.11

- Albums shared as zip files. With **Saved index** on, every audio file inside a zip post becomes a song of its own: searchable by title, artist or album, playable, and usable in playlists, **downloads** and BitChord's matching. Only the end of the zip and the first few KB of each song are downloaded to find them; the length, bit depth and sample rate come from each song's header. Songs stored without compression, which is most of them, play and seek like any other file. Split and password-protected zips are skipped. The Saved index line counts the zips still to open.
- With Copy into **main channel** on, zip posts are copied too, once each, and Skip duplicates knows a zip your main channel already has.

## 1.0.10

- **Main channel**: copy music from community channels into a private channel you own. Choose the main channel (only private channels you created) and the community channels under Main channel, then turn on Copy into main channel. Files are copied without "Forwarded from", oldest post first, and stay in your channel even if the original is deleted. Keep in sync, Lossless only and Skip duplicates can be turned on separately, and the pace is 1 to 500 files an hour. Everything is off by default, and each option shows a warning before it turns on: copying many messages can get a **Telegram** account limited.
- The **Saved index** line shows how far it's got, from Telegram's own count of each chat's music and files, with the time left: "Indexing: 12,920 files found · 6% · about 1 h 40 min left". The second pass through a chat's files, which finds FLACs posted as files, reads as many messages as the first but finds less music, so the count of files grows slowly there while the percentage keeps moving. BitChord's log gets a progress line every 25 pages.
- Each setting's explanation now sits under that setting, instead of in paragraphs after the last one.
- The Open BitChord button is gone; Back does the same.

## 1.0.9

- Check matches by sound (under Playback, off by default, with a warning when you turn it on). A **Telegram** file with the song's title but a length more than a few seconds off is usually another recording, but sometimes it's the same one with a longer fade or extra silence. With this on, the patch listens to the song's first 15 seconds as it plays, reads the start of the Telegram file and compares their audio fingerprints, the way Shazam does. When they're the same recording, starting at the same point, a message says so and the file plays from Telegram the next time the song does. Recordings that start at a different point are refused, so switching copies mid-song never jumps.

## 1.0.8

- A channel as a playlist: under Music library → Playlists and **downloads**, pick a chat and Save as playlist. It appears in BitChord's Library, named after the chat, oldest post first, and plays from **Telegram** like a search result. Saving it again replaces it with the chat's current files.
- Download a whole channel: Download all in the same place queues the files in BitChord's downloads. They're saved at BitChord's download quality, so set it to Lossless to keep FLAC files.
- Both take a chat's newest 1,000 music files, and the menu shows the number of songs and the download size first. A channel of 200,000 files would otherwise make a playlist BitChord can't hold, and a download of several terabytes.
- The **saved index** handles large channels. It's kept in a database now, instead of files that had to be read into memory and written out whole, which a channel of 200,000 files outgrew. A search takes a few hundredths of a second at that size. The index from 1.0.6 and 1.0.7 is deleted and built again.
- The Saved index line keeps counting while the page is open, against Telegram's own count: "Indexing: 12,300 files so far, of about 223,776". A full rebuild, which drops deleted files, now happens monthly instead of weekly.
- Once the saved index has every selected chat, BitChord's lookups are answered from it without asking Telegram. Telegram is only searched when the index has nothing, which also covers files posted since the last top-up. This keeps a channel download, or a long listening session, from adding up to a lot of searches.

## 1.0.7

- Fixed songs with a word like "You're" in the title playing from YouTube. BitChord drops apostrophes before asking add-ons, so it looked for "Nobody Knows You When Youre Down and Out". **Telegram**'s search matches whole words and found nothing, though the file was in the channel. When Telegram finds nothing now, the **saved index** is checked, and it treats "youre" and "You're" as the same word. Without the index, Telegram is searched again without such words (youre, dont, cant, aint, im and others), and the results are checked against the full title here.

## 1.0.6

- **Saved index**: the patch keeps a list of the music files in your selected chats on your phone. It's built slowly in the background, one page of 100 files every few seconds and only while nothing else is searching, so it doesn't add to **Telegram**'s limits. It's topped up with new posts every few hours and rebuilt weekly, which drops deleted files. When Telegram's search is rate limited, busy or times out, BitChord's lookups are answered from it, so songs can still play from Telegram. The **format legend** and name suggestions search it instead of Telegram once it's built. It's on by default. Turn it off under Music chats → Saved index, which also deletes it. BitChord's log notes when a search was answered from the index.

## 1.0.5

- When **Telegram** limits your searches (a flood wait), a message says Telegram API rate limited and when searching starts again, so you know why songs are playing from other sources for a while. It appears once per pause, not once per search. [Troubleshooting](docs/TROUBLESHOOTING.md#telegram-api-rate-limited-appears) explains what to do.

## 1.0.4

- Fixed songs playing from YouTube when you have them on **Telegram**. Since 1.0.2, the **format legend** and name suggestions run their own Telegram searches in the background. When they were running, BitChord's lookup for the song you were about to play was turned away with "Telegram search is busy", and BitChord fell back to YouTube or its cached copy. The patch only let two searches run at once and refused the rest straight away. Now BitChord's lookups wait up to 10 seconds for a free search, background searches step aside while BitChord is looking something up, and up to three searches can run at once. This was the patch's own limit, not Telegram's. When Telegram asks for a pause (a flood wait), no search is sent until the pause is over, as before.
- BitChord's log (Player ••• → Copy log) now shows which files each Telegram search found, which ones were skipped as a different song, and when Telegram asked to pause searches. That makes reports like this easier to look into.

## 1.0.3

- Fixed a wrong song playing from **Telegram**. BitChord accepts a file whose title only *contains* the song's title, so "How I'd Love To Love You" was played for "L-O-V-E". Telegram files now need the same title (brackets and punctuation aside) within 10 seconds of the song's length, or a containing title within 3 seconds. Other sources are matched as before.

## 1.0.2

- **Format legend** for every format: besides FLAC 16bit and 24bit, songs now show MP3 320kbps, AAC 256kbps, OPUS 160kbps and so on. The legend is a small white label on a translucent grey chip.
- Legends in the main search: when you search, the patch also searches your selected **Telegram** chats for the same words, once per search, and matches the results by title, artist and length. Songs you have on Telegram show their format right in the search results, and the legend appears on rows already on screen as soon as the match comes in.
- The project has a website at [hexclyde.github.io/bitchord-morphe-patches](https://hexclyde.github.io/bitchord-morphe-patches/), so it can be found through Google.

## 1.0.1

- The **Telegram** page's options are switches now, and flipping one no longer jumps the page back to the top. Other actions on the page keep your place too.
- **Play surround as stereo** is off by default. Android's own mixing is the default again; turn it on under Playback if you prefer the patch's mix.
- The **format legend** remembers songs that have played in a lossless format, so it also shows in BitChord's main search, library and queue, not only in the Addons tab. While a song is playing from a lossy stream, it shows nothing.

## 1.0.0

- Audio quality: FLAC, WAV, AIFF and ALAC files are reported to BitChord as lossless, and 24-bit files as hi-res. Other files are reported as high or low quality from their bitrate. BitChord uses this to choose between your file and other sources.
- Album covers: search results show the cover **Telegram** has for each file.
- Names from file names: files without a title tag, like `03 - Linkin Park - Numb.flac`, show up as "Numb" by Linkin Park, so BitChord can match them. Track numbers and tags like "(Official Audio)" or "[320kbps]" are removed.
- **Format legend**: a small "FLAC 16bit" or "FLAC 24bit" after song titles in search results, the mini player and the queue. Telegram files are labelled from their own header; songs from other sources from the format BitChord was given. The bit depth and sample rate also go to BitChord, so Show **stats** for nerds is exact.
- Smooth quality switch: when BitChord finds a better copy of the playing song, such as your Telegram FLAC after it started from YouTube, the new copy is loaded on BitChord's standby player and crossfaded in at the same point instead of restarting the player. If that can't happen (**Automix** mid-blend, for example), the switch happens the old way.
- **Play surround as stereo**: 5.1 and 7.1 files are mixed down to stereo inside BitChord's player, before its equaliser and effects, the same way `ffmpeg -ac 2` does it. The file isn't changed, nothing is converted ahead of time, and it works for every song BitChord plays.
- Each of these can be turned off under Unofficial Telegram → Track details or Playback.

## Before 1.0.0

- Saving a corrected name, or accepting a suggestion, also removes BitChord's saved copy of that song. A song BitChord has already saved from YouTube plays from that copy without checking other sources, so removing that copy allows the renamed **Telegram** file to match.
- The **Track names** card updates while it's open: new suggestions appear without reopening the page.

- Track names: fix a Telegram file's title or artist on your phone without uploading it again, under Unofficial Telegram → Track names. BitChord then finds and matches the file under the corrected name.
- Suggested fixes: when a song plays from another source and one of your chats has a file by the same artist, about as long, whose title is a few letters off, the patch suggests the correct name. Nothing changes until you accept.
- Both can be turned off on the same page.

- Removed the extra "Unofficial Telegram setup" launcher icon. Telegram setup is under Settings → Account & integrations → Unofficial Telegram.
- Tapping Telegram (on device) under Sources now opens the Telegram settings page instead of an editor showing an internal address.
- The package name is unchanged, so 0.2.1 installs over 0.2.0 when you patch with the same Morphe signing key.

- Sign in to Telegram with your own API credentials and account.
- Search selected chats and stream audio with seeking.
- Browse channel posts and official sponsored messages in Channel View.
- Adjust the Telegram audio cache and view the playing source and bitrate.
- Choose an installation name; the default is Bitchord+.
