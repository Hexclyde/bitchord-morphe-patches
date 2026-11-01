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
| Component regression | I pass the complete 543-test Android component suite, including recording ranges, caching, download calls, installed features, filtering, gears, dependencies, Undo and persistence. |
| Host regression | I pass the full host test runner, including 7,127 local-ranking checks, 1,032 Sing output checks and storage, peer, download and overlay checks. |
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

## Completion status

I verify a native Telegram-to-Telegram AutoMix at 1.25× speed: both selected source tracks are measured, the planned 4.359-second blend overlaps, the queue advances, pitch stays normal and the original queue/settings are restored. The hires community provider advertises a lossless-output requirement that Waydroid’s virtual speaker does not satisfy; I retain that gate and do not claim its native playback passed.

I am completing final APK checks and the source package. I keep the exact final build and checksums with the local deliverables.
