# Known limitations

I target the standard BitChord 1.9 APKs for arm64-v8a, armeabi-v7a and x86_64. I check all three patched APKs and run scoped native tests in x86_64 Waydroid. I have not qualified this version on physical ARM phones, TV, Wear OS, split APKs or already patched inputs. I describe the exact evidence in the [verification report](VERIFICATION-1.1.0-beta.18.md).

## Playback and effects

- I keep new playback options off by default. Bit-perfect output bypasses effects, including Sing, Pitch control, Loudness level, Headphone correction, the sleep fade and JamesDSP. Battery saver also stays off for bit-perfect output and AutoMix.
- I keep playing over other apps with Play alongside enabled, so navigation prompts no longer duck the music. I still need physical phone, ringing, VoIP, headset and Bluetooth qualification; Android communication mode does not identify every third-party call reliably.
- I allocate Steady playback's read-ahead buffer in Java memory. Larger limits consume more memory, particularly with two players. Offline retry extension applies to the qualified progressive loader; I do not claim every HLS/DASH error waits indefinitely.
- I use BitChord's selected-source router for Source-independent AutoMix. Missing, incomplete or unsupported analysis falls back to the ordinary crossfade. I cannot make an unavailable community provider return audio. I keep the supplied providers' live results in the verification report.
- I verify automatic source replacements across opening, middle and ending windows, duration and timing drift. This can add network traffic and time before an upgrade; the old copy keeps playing. These checks do not prove an identical master or a lossless origin. I retain the original source when proof or buffering fails.
- I add about 1.60 seconds of latency with Sing's separation model. Enabling it or returning from a nonstandard speed/pitch needs a fresh history; changing modes can cause a time discontinuity. I use the filter when inference cannot keep up. I have not completed a physical-phone listening or thermal comparison. I keep model timing, licensing and Spleeter conversion limits in the [feature guide](BETA18-FEATURES.md).
- I cannot identify which other app captures audio from Android's capture notification, so the JamesDSP status cannot certify that engine. I need its optional auto-start permission for **Start JamesDSP**; otherwise I open it directly.
- I show the Audio/Video switch only for eligible YouTube Music songs, outside Lyrics and Queue. Files and add-on tracks have no paired video version. Physical-phone timing remains unqualified.

## Telegram and recording details

- I support one Telegram account and existing-account login. I cannot create an account or support every Telegram login method.
- I search up to 200 tracks across selected, accessible chats. Regular search can still choose YouTube; **Search → Addons** exposes direct Telegram results. BitChord requires **Lossless** streaming quality for add-on playback on the active connection.
- I do not play protected, secret or self-destructing media. Cached audio and saved downloads can remain after sign-out or chat removal; I clear those separately.
- I list albums from a chat's newest 5,000 files. Copying oldest posts first requires an indexed history; newest-first copying can begin immediately. A channel with 200,000 files can take about an hour to index.
- I inspect FLAC and WAV files, stored zip entries and supported cue albums for lossless-origin clues. I do not certify ALAC, AIFF, APE or WavPack with that check. High-rate MP3 conversions and recordings with naturally limited treble can be inconclusive or misleading.
- I require a personal AcoustID key for identification, and that service must know the recording. I do not identify zip/cue tracks through it. I expose peer-reported metadata as reported information rather than verified file content.
- I serve cue tracks as uncompressed WAV, which can make a saved track larger than its original FLAC. I do not support APE/WavPack cue albums.
- I recognize TrueHD Atmos in MKA files; AC-4 needs a compatible device decoder. Height playback needs compatible hardware and an appropriate master.
- I open Telegram links and channel videos in Telegram. I do not render every sponsored-message format, custom emoji or link preview. Telegram's own error messages can remain in English.

## Downloads and storage

- I use FLAC Downloader only for downloads, with its actual FLAC, MP3 320 and MP3 128 choices. Provider preparation, verification and availability can change. I verify one live FLAC download and protocol fixtures; I do not claim every requested format works on the live service or silently downgrade a failed request.
- I retain the qualified player download entry, 200-track batch limit, explicit public-share inventory and Telegram archive/removal/restore workflow. Initial source plans do not survive process death, and public sharing pauses after a restart. I document the workflow in the [Downloads guide](DOWNLOAD-MANAGER.md).
- I consider all enabled, eligible download sources for Smart downloads, using the selected quality and recording matcher. Android may defer its unmetered-network job. I remember handled songs, skip deleted downloads and never automatically delete music. A failed transfer remains eligible for retry; cancelling my job does not stop unrelated transfers.
- I protect playing, queued, downloading and recently used cache resources. Unknown or unavailable state stays protected. Media3 has no atomic check-and-remove operation; a new reader can start in the small interval after the final check and can fetch cache bytes again.
- I keep Storage Sense cleanup rules disabled initially and separate permanent saved-file deletion from cache cleanup. I use the owning cache or TDLib API, retain credentials and session files, and leave unsupported patch/Soulseek transfer caches read-only. I have not tested deletion of a populated Telegram cache in this session.
- I preserve full source/file metadata with Mixed-source playlists. Stock export cannot retain every extension field; deleting a referenced local file does not turn it into a remote stream.

## Soulseek and compatible peers

I require explicit source/result selection for Soulseek downloads. Search cancellation, no results or failure does not silently download from YouTube, and an imported peer file does not mark an unrelated YouTube row as saved. Peer speed, slots, queue and audio specifications are reported metadata; the actual transfer determines availability. I use a bounded initial shortlist and a short session/policy cache, so later search replies may require another search.

I preview an explicitly selected supported audio file through an isolated player and temporary transfer. Keep download reuses buffered bytes. A preview can fetch the entire file, and comparison against a chosen local reference does not certify a master or lossless provenance. I list formats and native-decoder limits in the [preview guide](STREAMSEEK-PREVIEW.md). I still need physical-phone focus/background and remote-peer qualification.

I discover compatible app public keys through opt-in profiles and PEX, without implying trust or file access. I do not search a BitTorrent DHT or join torrent swarms. I keep at most 512 signed profiles, expiring within five minutes, and read an explicit scan for ten seconds. Publishing a profile requires a separate opt-in; an announcement does not prove a peer is reachable or owns a requested recording. Real paired-peer and debrid account tests remain incomplete.

## Playlists, language and accessibility

I generate Smart playlists locally from available device metadata. Missing genre/year/tempo tags can leave too little evidence; I show short results and a separate unverified review. Mood and negation vocabulary covers English and Spanish; names and titles match across scripts. I offer an external text service only through explicit setup and selection.

I retain manually reviewed Spotify/YouTube Music playlist membership sync, requiring editable playlists and connected accounts. I do not include scheduled write-back, name/order sync or other service connectors. Unavailable tracks, duplicates and incomplete reads need review. Live write-back remains pending as requested. I describe it in the [playlist guide](PLAYLIST-TOOLS.md).

I supply new interface labels through the existing 20-language pipeline. I have not obtained native-speaker review for every translation or run a full TalkBack session. I test theme, font scaling, filtering, focus, gears, dependencies, Undo and persistence in components and perform scoped native interface checks; those checks do not qualify every physical-device layout.
