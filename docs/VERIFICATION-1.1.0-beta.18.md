# Verification: 1.1.0-beta.18

I build these patches for the original BitChord 1.9 APKs. I preserve my existing signing key and app data for Android testing. I distinguish framework/component tests, patched-APK inspection and device playback below.

## Confirmed checks

| Area | Evidence |
| --- | --- |
| Provider conversion | I reproduce and fix an MP3 preparation response incorrectly rejected while the provider was still converting its FLAC mirror. |
| Provider capability | I exclude FLAC Downloader from playback and playback search, retain its download enable state, and retain FLAC, MP3 320 and MP3 128 choices. |
| Native recording reader | I replace removed Kotlin Pair getters with the fields present in the shrunk 1.9 APK. I also correct bounded-range verification, cached-reference reads, stale audition positions and an R8-reused service register. I verify real two-player handovers in Waydroid. |
| Downloads | I verify a provider FLAC file in Waydroid: 31,664,378 bytes, 16-bit/44.1 kHz stereo, 274.2857 seconds, with a complete decode. I do not treat a successful fixture as proof of every live provider format. |
| Storage inventory | I inspect actual playback/add-on cache resources and a saved file in Waydroid. I protect playing resources and leave both automatic cleanup rules off. Telegram's indexed cache is empty in this session. |
| Peer page | I inspect the native stopped state, incoming-offer empty state and existing completed files in Waydroid. I retain the files and account sessions. |
| Local playlists | I generate four real Linkin Park matches from a request for 25 in Waydroid, save the reviewed playlist and delete only that uniquely named temporary playlist. |
| Sing playback | I play the saved FLAC with the separation model in Waydroid, observe an active engine and an 873 ms inference, seek near the end and advance into the next item without a playback error. I restore the 16-item queue and prior settings. |
| Scheduled downloads | I run Android JobScheduler through the production job, source matcher and host download coroutine against a controlled local WAV add-on. I compare saved PCM with the fixture, verify its persisted source id/name and removal of that record, delete only the test file and restore the queue, download settings and listening data. The private instrumented APK permits the loopback test transport; its network-policy change is excluded from the MPP. I do not treat this as real-provider qualification. |
| Independent selections | I inspect 11 independent patch selections, including Telegram-only, AutoMix-only, Play alongside, Steady playback, Smart downloads and source helpers without Telegram. All pass their applicable source-isolation and DEX checks. |
| APK architecture | I patch and inspect arm64-v8a, armeabi-v7a and x86_64 inputs. All three pass DEX control-flow, register/return contracts, APK signatures and exact native-library ABI/hash/dependency checks. I also inspect a playback-only selection without Telegram. |
| Component regression | I pass the complete 743-test Android component suite, including recording ranges, caching, download calls, installed features, filtering, gears, dependencies, Undo and persistence. |
| Host regression | I pass the full host test runner, including 7,172 local-ranking checks, 1,032 Sing output checks and storage, peer, download and overlay checks. |
| Audio focus | I use real Android AudioManager communication/normal mode IPC in Waydroid. The production listener pauses and resumes the same native player; disabling the feature or making a repeated manual pause prevents recovery. I preserve speed, pitch, volume, queue, settings and audio mode. I place no phone call and do not qualify a physical telephony/VoIP service. |
| Prepared queue playback | I start Numb on YouTube, enable the Telegram source, and append Teen Spirit plus a duplicate Numb with separate queue identities. Both upcoming entries start audibly from their prepared hifi renditions, without a legacy upgrade. The original entry retains its YouTube cache key/source while they prepare; Stats follows the actual Telegram copies and speed/pitch stay 1. I restore the original 19-item queue, autoplay and settings. |
| Replacement cache | I keep the proven complete YouTube cache while the Telegram replacement is incomplete, play the exact prepared copy, complete it after its temporary queue entry leaves playback, and observe zero old YouTube spans while the complete Telegram resource retains its actual source. I restore the original queue and settings. |
| Source records | I pass 83 host checks and 32 component tests over real cache databases, preference reloads, duplicate queue entries, concurrent source captures and optional runtime absence. I inspect actual DEX argument flow and verify six deliberately broken mutations fail. |
| Queue preparation | I pass 94 preparation, 58 source-selection and 16 recording-reader component tests, plus 20 recording-identity host checks, including late results, duplicate entries, changed files, offline proof and source disabling. |
| Pitch baseline | I confirm the pitch feature is off, speed and pitch factors are 1, and the saved pitch is 0. I retain the queue. |

## Separation benchmark

I run the host's pinned 9,054,206-byte quantized model with two threads, CPU arena off and memory-pattern optimization off. Each inference represents 22.291 seconds of audio.

| Environment | Observation |
| --- | --- |
| Desktop, six runs | I measure a median of 0.451 seconds under concurrent build load. |
| Waydroid Android 13, x86_64, four runs | I measure 0.185, 0.153, 0.127 and 0.162 seconds; outputs are finite. |
| Spleeter FP32 conversion | I produce a checked 39,308,558-byte ONNX file and measure a 9.793-second desktop median. |
| Spleeter FP16 conversion | I produce a checked 19,664,511-byte ONNX file and measure a 3.518-second desktop median. |

I do not treat Waydroid as a physical-phone thermal or listening qualification. I have no verified public URL for the converted Spleeter files and provide the reproducible conversion tool instead of a nonworking in-app download.

## Live-service limits

I probe the eight supplied community source URLs. At this pass, soundfean and the two gglos hosts return 404, lastbabe returns 410, the eljohnzc add-on URL returns 404, Bluebit search returns 502 and eljohnzc mono search returns 500. The hires add-on returns a manifest, search results and a stream URL. I cannot claim playback or AutoMix passed for sources that cannot return a playable track.

I retain external playlist sync but leave live Spotify/YouTube Music write-back pending, as requested. I do not have credentials for debrid or paired physical peers. I have not qualified real phone/VoIP calls, Bluetooth devices, physical ARM audio timing or cleanup of a populated Telegram cache.

## Telegram handover

I reproduce the removed Pair-getter crash and fix the actual shrunk-runtime binding. I then reproduce the stalled audition and correct full-file verification of range requests, cache-reader reuse and the audition's stale seek. A native run exposes a separate register-lifetime error: the commit passes an audio format where the smooth-swap helper needs the playback service. I capture that service before its register is reused and consume it only for that commit.

I verify “Numb” upgrading from YouTube Opus to Telegram FLAC 24-bit/48 kHz and “Smells Like Teen Spirit” upgrading to FLAC 16-bit/44.1 kHz. Both use the two-player path, advance its generation, keep playing throughout the sampled handover and restore the queue and settings. I verify the clean APK separately. Its host log records zero first-audio silence covered by the crossfade, and my local output-monitor capture contains no quiet 20 ms window below −80 dBFS across the 6.3-second handover interval. I do not equate that signal check with a perceptual or physical-phone listening qualification.

I measure 13.5–16.1 seconds for the recording check in unloaded Waydroid runs and about 20–22 seconds from initial playback to the completed handover. Concurrent build/component load produces a 28.9-second check. Those runs reuse Telegram media already fetched during testing; cold-network performance remains unqualified. I time each window and find about 4.7 seconds in fingerprint calculations. Reusing repeating sinc-filter phases reduces that work to about 0.6 seconds; a subsequent native check takes 10.4 seconds and hands over in about 15 seconds, without reducing windows or thresholds. I also pass 35 fingerprint checks across ordinary, high and unusual sample rates.

## Prepared-entry upgrade protection

I reproduce an older in-flight Telegram upgrade replacing an entry already playing a prepared lossless rendition. The device remains audible but switches the exact queue identity and cache marker to the legacy rendition. I guard both upgrade checks against the current entry’s prepared binding, so plain entries and their ordinary upgrade path remain eligible. I verify the corrected prepared entry stays audible with its exact identity through the longer device run; the replacement cache then completes and the old YouTube spans are reclaimed.

## Whole-track preparation

I replay the exact “Somewhere I Belong” sources using the native decoder with both a fresh and a complete cached YouTube reference. Both runs produce three successful fingerprints: 60.4%, 23.3% and 42.6% matching hashes. The measured offsets are 0, 85.5 and 160 ms, exceeding the strict 80 ms live-swap span. Container durations differ by 132.8 ms over about 214 seconds. After accounting for the duration ratio, the residual span is 37.8 ms. I retain the strict live-swap decision and introduce an explicitly separate, approved whole-track preparation policy.

I reproduce the misleading missing-source message before correcting it and pass focused preparation tests. I also reproduce a cold upcoming-period timeout: its recording check finishes after the 20-second foreground budget, while the current song remains audible. I give only confirmed future entries outside protected decks a bounded longer wait, retain their awaited jobs across queue updates, and preserve the original deadline when selection changes.

I verify the r1 APK in Waydroid with “Somewhere I Belong” initially audible from YouTube and separate upcoming Teen Spirit and Somewhere entries. Both upcoming entries start audibly from their verified Telegram renditions. The current YouTube entry retains its original identity during preparation; Stats follows the actual audible source, with speed and pitch at 1. I retain the complete YouTube resource until the verified Telegram resource completes, then observe zero obsolete YouTube spans and the complete Telegram source record. I restore the 22-item queue, preferences, autoplay and source flags.

I reproduce overlapping recording and transition decode working sets on Waydroid’s 192 MiB Java heap. I pass 82 focused component tests covering shared admission, lock order, coroutine resolution, cancellation, cache-only reads, bounded DSP, memory leases and transition labels. I also reproduce equal stream objects overwriting metadata snapshots and verify independent weak identity storage. I inspect the compiled analyzer’s 29.76-second fallback and native vocal window calculation to qualify the 30-second model-window bound used by the memory estimate.

## Playback memory

I reproduce a separate out-of-memory failure with Steady playback enabled on Waydroid's 192 MiB Java heap. A private heap snapshot contains about 118.6 MiB of Media3 allocation blocks and less than 1 MiB of float arrays. I trace about 64.3 MiB to the music player and another 54.3 MiB to BitChord's animated-artwork player, whose stock target is about 125.1 MiB. This capture distinguishes player buffering from analysis PCM.

I reproduce an analysis-admission regression where a recording fits the current free heap before the player buffers fill. I reserve room for those future buffers; the source-independent analysis suite passes 19 tests. I also reproduce broad Telegram preparation invalidation with real TDLib content updates and pass 310 checks for affected files, unrelated messages, weak-watch capacity and session reset.

I pass 75 host travel-feature checks and reject seven malformed loading-gate bytecode variants. I separately pass the native music-to-music transition with all three players present, preserve normal speed and pitch, and restore the 23-item queue, source flags and preferences. I observe a prepared Teen Spirit description at revision 4 remain valid after the global Telegram revision reaches 6: its file watch, source terms and identity still match, and it starts from Telegram FLAC. I keep the ten-song natural playback run pending until it finishes.

## Prepared-rendition bitrate

I reproduce missing bitrate on a prepared Telegram FLAC when the native measurement looks up the original queue URI. I keep the native background size/duration calculation and use the exact prepared rendition for both its measurement-cache key and its captured size-lookup URI. The old APK fails the new contract; the rebuilt APK passes, and three malformed URI-flow variants are rejected.

I verify “Numb” playing from Telegram FLAC 24-bit/48 kHz with an encoded file average of 1,919 kbps. The rendition's measurement-cache entry contains that value while the original URI has no measurement entry. The player displays the approximate-average label and retains speed and pitch at 1. I leave missing size or duration unknown; instantaneous frame bitrate is outside this measurement.

## Native return paths

I reproduce a cache-identity failure on the device that component tests and hook-count checks miss: an existing branch jumps directly to a return, bypassing a call inserted before it. I replace the return instruction with the transform call so existing labels enter the hook. I check every reachable normal return path for the cache key, Song tagging, source ranking and download-only provider filtering. I also check route capture and Stats arguments against branch, switch and handler edges. Deliberately bypassed hooks fail these checks even when their call counts remain unchanged.

## Completion status

On an earlier beta.18 candidate, I verified a native Telegram-to-Telegram AutoMix at 1.25× speed: both selected source tracks are measured, the planned 4.359-second blend overlaps, the queue advances, pitch stays normal and the original queue/settings are restored. The hires community provider advertises a lossless-output requirement that Waydroid’s virtual speaker does not satisfy; I retain that gate and do not claim its native playback passed.

I separately test the ordinary upgrade with preparation disabled: I keep Telegram off until YouTube Opus is audible for at least three seconds, enable Telegram, and observe a native generation change to Telegram FLAC at 48 kHz stereo with at least six further audible seconds. Speed and pitch remain 1 and no playback error occurs. I restore the 19-item queue, source flags, settings and preparation flag. Telegram was disabled before this test, and I retain that original state.

I include the rebuilt MPP, complete source, installation instructions, metadata and checksums in the local package. The MPP SHA-256 is `23fd39054023342ffe40035fc61c6c25314874f88909a399e6245cc3bdc2b16f`. Windows publishing helpers remain ZIP-only. I perform no GitHub upload, branch push, tag or release.
