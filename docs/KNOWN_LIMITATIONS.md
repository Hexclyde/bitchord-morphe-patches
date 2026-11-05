# Known limitations

The patches target original BitChord 1.9 APKs and the 1.9.1-beta1 source build for arm64-v8a, armeabi-v7a and x86_64. Physical ARM phones, TV, Wear OS, split APKs and already patched inputs have not been qualified.

## Playback and effects

- BitChord 1.9.1-beta1 compatibility is tied to source revision `ee9129d`. Other 1.9.1 versions are outside the supported version list.

- Optional playback features start disabled. Bit-perfect output bypasses Karaoke, Pitch control, Loudness level, Headphone correction, sleep fades and JamesDSP. Battery saver yields to bit-perfect output and AutoMix.
- DJ mode orders the upcoming queue while shuffle is off. With shuffle on, BitChord controls playback order.
- Play alongside other apps prevents other audio from ducking music. Android communication mode cannot identify every third-party call; phone, VoIP, headset and Bluetooth behavior needs physical-device checking.
- Steady playback uses Java memory for read-ahead. Larger settings consume more memory, including spare and artwork players. Extended offline retries apply to progressive loading; HLS and DASH errors can still stop playback.
- Read-ahead and background analysis wait when the separation model leaves too little free heap.
- MPEG-TS files can be previewed with Streamseek. Track analysis and automatic recording replacement are unavailable for MPEG-TS.
- BitChord already provides add-on crossfading. Automix Editor supplies selected-recording analysis to BitChord’s native planner. Unsupported, incomplete or unavailable analysis uses a basic crossfade. Provider availability and evidence determine which transitions are possible.
- Automix Editor’s **Before** preview requires captured native timing for the current recording pair. Native filtering, phase locking and other effects that its private audition cannot reproduce make Before unavailable.
- With YouTube Music enabled, automatic source replacement checks opening, middle and ending audio windows, duration and timing. This adds traffic and can delay an upgrade while the existing copy plays. It cannot certify an identical master or lossless origin. A failed check or insufficient buffering leaves the current source playing. With YouTube Music disabled, initial playback uses the other source’s metadata match without fetching a YouTube audio reference.
- With **Play the best source first** on, an unprepared song can spend up to 20 seconds checking sources before falling back. A prepared queue entry skips that wait; subsequent network resolution can add time.
- Karaoke separation adds about 1.60 seconds of latency. Enabling it or returning from a different speed or pitch needs fresh audio history and can cause a discontinuity. The filter handles unavailable or late inference. Physical-phone listening and thermal behavior remain unchecked. See [Karaoke settings](BETA18-FEATURES.md#karaoke-separation-model).
- The Karaoke control sits directly above the translation button in the lyrics, and only with the Karaoke patch installed. It uses BitChord's frosted-glass effect when available and a plain translucent capsule otherwise. It hasn't been tried on a phone yet.
- Android's capture notification cannot identify JamesDSP as the capturing app. **Start JamesDSP** needs its optional auto-start permission; otherwise BitChord opens it directly.
- The Audio/Video switch applies to eligible YouTube Music songs outside Lyrics and Queue. Add-on tracks and local files have no paired video version.
- A cold video switch can wait for version lookup, Smart version alignment and buffering. The switch timing log separates lookup and dispatch; it does not establish when the first video frame appears.
- Matching a YouTube song to a local music file needs readable title, artist and duration tags. Live, remix and other version labels must agree. Untagged files remain playable directly from the library.
- Lyrics timing depends on the provider's result for the active version. Different edits can need a timing offset; keeping a provider choice does not establish that its timestamps match every edit.

## Telegram and recording details

- Live catalogue changes depend on TDLib delivering updates while connected. ZIP inspection follows the indexing Wi-Fi and charging settings; a stopped app cannot process new posts until it runs again.
- One existing Telegram account is supported. Account creation and some Telegram login methods are unavailable.
- Search returns up to 200 tracks across selected accessible chats. Preparation considers eligible enabled sources and can use YouTube while it is enabled when no verified copy is ready or delivery is too slow. BitChord requires **Lossless** streaming quality for add-on playback while YouTube Music is enabled; turning YouTube Music off allows other enabled sources at their available quality.
- Protected, secret and self-destructing media cannot play. Cached audio and saved downloads can remain after sign-out or chat removal and need separate cleanup.
- Albums use a chat's newest 5,000 files. Oldest-first copying requires indexed history; newest-first copying can begin immediately. Large channels take longer to index.
- Lossless-origin inspection covers FLAC, WAV, stored zip entries and supported cue albums. ALAC, AIFF, APE and WavPack are outside that inspection. High-rate MP3 conversions and naturally limited treble can make its result inconclusive or misleading.
- Identification needs a personal AcoustID key and a recording known to that service. Zip and cue tracks cannot use it. Peer-reported metadata does not certify file content.
- Cue tracks use uncompressed WAV and can occupy more space than the original FLAC. APE/WavPack cue albums, split archives and password-protected archives are unsupported.
- TrueHD Atmos in MKA is recognized. AC-4 needs a device decoder; height playback also needs compatible hardware and a suitable master.
- Telegram links and channel videos open in Telegram. Some sponsored-message formats, custom emoji and link previews cannot render; Telegram errors can appear in English.

## Downloads and storage

- FLAC metadata preservation keeps existing embedded tags when no non-empty replacement is supplied. It does not fetch missing release dates, restore tags already removed from older files, or add every embedded field to Downloads cards.

- BitChord’s native saved-file lookup can discard an index entry when an unavailable drive is treated as a missing file.
- FLAC Downloader is unavailable in the patch catalogue. Existing saved files remain accessible from Downloads.
- Batch download selection is limited to 200 tracks. Initial source plans do not survive process death, and public sharing pauses after a restart. See [Downloads](DOWNLOAD-MANAGER.md).
- Per-download quality asks during eligible manual downloads. Scheduled playlist synchronization uses the global quality without a dialog.
- The player Download control needs a usable source or readable local file. Alternative matching requires recording metadata and duration; reported quality and duration do not certify the source file's master. A transfer can still fail after its initial probe and need Retry.
- Resuming a paused HTTP download can restart the transfer from the beginning rather than continue from a byte offset.
- Smart downloads considers enabled eligible sources and the selected quality. Android can defer its unmetered-network job. Failed transfers can retry; deleted downloads are skipped, and the feature never deletes music automatically.
- Storage Sense protects playing, queued, downloading and recently used resources, including resources with unknown state. Media3 has no atomic check-and-remove operation: a reader can start after the final check and fetch missing cache bytes again.
- Cleanup rules start disabled. Saved-file deletion is permanent and separate from cache cleanup. Unsupported patch caches and Soulseek's transfer cache are read-only. Populated Telegram cache cleanup needs further device checking.
- Cache entries from before source tracking, or entries with mixed or ambiguous bytes, show an unknown source. A fresh confirmed resolution can identify a new copy. Codec, title and file extension cannot establish origin; Opus alone does not establish YouTube.
- Turning off YouTube Music refuses cached audio with unknown origin. A verified copy from another source remains playable. Re-enabling YouTube Music makes an unchanged legacy cache entry available again.
- Mixed-source playlists depend on access to their original files and sources. Stock export cannot carry every patch metadata field; deleting a local file does not turn its playlist entry into a stream.

## Soulseek and compatible peers

Soulseek downloads require an explicit result choice. Search cancellation, failure or no results never selects YouTube automatically. Imported peer files do not mark unrelated YouTube entries as downloaded. Peer speed, slots, queue and format are reported information; only a transfer establishes availability. Later search replies can require another search.

Previews use an isolated player and temporary transfer. Keeping a download reuses buffered bytes, but a preview can fetch the whole file. Local-reference comparison cannot certify a master or lossless origin. Platform codec support for comparison can be narrower than [preview decoding](STREAMSEEK-PREVIEW.md). Physical-phone focus, background behavior and remote peers need further checking.

Peer discovery announces opt-in app keys, not trusted identities or file access. It stores at most 512 signed profiles for up to five minutes and scans for ten seconds. A profile does not prove reachability or recording ownership. Paired-device networking and account-backed debrid transfers need further checking.

## Playlists, language and accessibility

Smart playlists depend on device metadata. Missing genre, year or tempo tags can leave few matches. Mood and negation vocabulary covers English and Spanish; artist and title matching supports other scripts. An external text service requires explicit setup and selection.

Spotify/YouTube Music membership sync requires connected accounts and editable playlists. It runs manually, with no scheduled writes, playlist-name or order synchronization, or other service connectors. Unavailable tracks, duplicates and incomplete reads need review. Live write-back needs further checking; see [Playlist tools](PLAYLIST-TOOLS.md).

Some explanatory labels fall back to English. Translation, TalkBack and physical-device layout coverage is incomplete.
