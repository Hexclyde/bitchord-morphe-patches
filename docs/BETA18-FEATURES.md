# Native playback, downloads and storage

I build beta.18 for BitChord 1.9. I keep every new selectable playback patch off by default and use native Android controls, BitChord's theme and its green capsule switches. I keep the original README, installation guide and existing feature guides alongside this page.

## Playback

I use **Play alongside other apps** when I want music to continue over other apps' audio. I preserve the host's requested focus per player, keep its noisy-headphone policy, and pause during ringing, phone and communication modes. I discard automatic recovery when the track changes or the feature is disabled during a call.

I use **Steady playback** to raise the host's 8 MiB buffer limit. I offer 16/32/64/96 MiB on unmetered connections and 8/16/32/64 MiB on metered connections. I apply changed limits at Media3's next loading decision on its playback thread. I retain settings when disabled and restore the host's limit. I increase retries only while the network is absent; I preserve normal online error handling.

I use **Smart downloads** to consider songs with at least three plays in the last three months. I search enabled download-capable sources, check recording metadata through BitChord's matcher, and rank matches in the selected Download quality. I pin the selected source and format, record the selected identity, and skip previously queued or saved songs. I never automatically delete music or download a song again after it has been removed. I consider at most 25 songs per run, about twice daily on an unmetered network, with charging required by default and a free-space guard. I run transfers inside Android’s scheduled job, without relying on a background foreground-service start. I stop my coroutine scope if Android cancels the job or the feature is disabled. I retain unsuccessful songs for a later retry and remember completed or explicitly cancelled songs. I do not cancel unrelated transfers.

## Prepared sources and cache identity

I use **Play the best source first** in Unofficial Telegram settings to prepare upcoming songs against eligible enabled sources, including community add-ons. This enhancement is enabled with the integration; the three newly selectable playback patches remain off by default. I check the recording and delivery before choosing a copy, respect source selections and quality limits, and use YouTube when no other verified copy can play or delivery is too slow. I keep FLAC Downloader exclusively in the download catalogue.

I retain each queue entry’s chosen rendition while it plays. A duplicate song can prepare a better copy without changing the bytes or source identity of an entry already playing. I bound preparation to two upcoming songs, perform network and recording checks away from the main thread, and discard stale work when sources or settings change.

I retain a complete YouTube cache copy until the verified replacement is complete. I protect active readers, keep encodings in separate cache resources, and never remove a saved download as part of replacement. I fill eligible departed replacements in bounded chunks before reclaiming obsolete YouTube playback cache resources.

I record the actual source name separately for each cached rendition and completed saved file. Stats, Downloads and Storage Sense use those records; unknown or mixed provenance stays unknown. I distinguish advertised format information from measured details and keep source URLs and credentials out of these records.

## Sing: separation model

I retain the existing Filter method and level slider. I add **Separation model**, using the host APK's `vocals_umxhq_int8.onnx` asset, without adding model bytes to the MPP. I validate its size, SHA-256 and tensor interface before opening my own two-thread session. I disable the CPU arena and memory-pattern optimization and serialize inference and session closure independently of AutoMix.

I use 4096-sample Hann windows, a 1024-sample hop, weighted overlap-add and frequency mapping to the model's 44.1 kHz grid. I apply a bounded soft vocal mask behind a 1.5-second lookahead. Including the transform, latency is about 1.60 seconds at 44.1/48 kHz. I keep level zero bit-identical and retain the filter when the model is missing, late, unsupported or unavailable. I reset history on seeks and keep delayed frames across ordinary track boundaries. I bypass separation for a stream whose own speed or pitch differs from normal; bit-perfect output stays dry. I bound active engines, coalesce inference and release the session after in-flight work when Sing is disabled.

I benchmarked the 22.291-second model window separately on desktop and in x86_64 Waydroid. I keep the measured timings, conditions and limitations in the verification report. I have not qualified separation quality or timing on a physical phone.

I credit [Open-Unmix](https://github.com/sigsep/open-unmix-pytorch) by sigsep (MIT code). I reuse BitChord's existing quantized export, pinned to SHA-256 `a2be987b55a29bc149d3a6ae99b08175d81f85ee292a8ea21f96c3a473bc94cb`. The asset contains no explicit export licence metadata; I do not redistribute it in this source package or claim a separate licence for that export.

I provide [convert_spleeter.py](../tools/convert_spleeter.py) for [Deezer Research's Spleeter](https://github.com/deezer/spleeter). I verify the official 2stems archive hash, convert it through TensorFlow/tf2onnx, generate FP32 and FP16 ONNX files, and check the native tensor interface and finite output. I preserve frequencies above the model's approximately 11 kHz band. The [Spleeter paper](https://doi.org/10.21105/joss.02154) identifies both code and pretrained models as MIT licensed. I keep converted weights outside the MPP. I have no verified public URL for these converted files, so I do not offer an in-app download that would depend on an unpublished asset. I have not performed a listening comparison.

## Downloads and Storage Sense

I keep BitChord's four native download settings: quality, Wi-Fi restriction, export location policy and downloaded-playlist synchronization. I add a **Patch downloads** row immediately after them. I also provide a Downloads settings entry in Patches & tweaks, so I can reach the same native controls without scanning separate add-on pages.

I browse actual cache resources, not standalone cache fragments. For the host song cache I show keys, stored byte ranges and completeness, and reclaim data through the owning cache's `removeResource`. For Telegram I list indexed music files and use TDLib's deletion API, respecting active leases and recent use. I show locations I cannot safely manage as read-only, with a reason.

I recheck playing, upcoming, queued, downloading and recently used resources immediately before cleanup. If state is unavailable I keep the data. Media3 has no atomic check-and-remove operation; an already-open descriptor remains readable if playback begins in that small interval, and missing cache data can be fetched again.

I keep saved-download deletion separate and confirm it as permanent. I refuse unknown locations, session data, credentials, model files and active transfers. I compare duplicate saved files by SHA-256 of their contents, with limits and cancellation; matching names alone never justify deletion.

I leave age and size cleanup rules off by default. I run enabled rules at most once daily after app start and only against eligible caches. Opening the page never cleans anything. Soulseek's transfer cache stays read-only here because its own disconnect barrier is required to clear it.

## Smart playlists

I generate Smart playlists locally by default, using the songs, playlists, likes and listening metadata already on my device. I describe an artist, title, genre, mood, decade or tempo and review the matching songs and their reasons before saving. I do not invent missing tags or pad a short result; I review songs without supporting tags separately when I explicitly choose to. Local generation makes no source searches or external text-service requests.

I can explicitly select an optional compatible HTTPS text service for suggestions. I keep its key in encrypted local storage, never upload audio, and send the prompt only after **Generate**. In this mode I review real installed-source matches, correct or skip uncertain rows and explicitly save the final device playlist. Cancelling or leaving the page prevents stale work from publishing results.

I credit [PixelMusic](https://github.com/ianshulyadav/PixelMusic) by ianshulyadav (Anshul Yadav, GPL-3.0) for its local playlist language processing and ranking, which I port to native Java for BitChord. I adapt its stemmer, vocabularies, genre taxonomy, scoring and rarity weighting at commit `991f7c5abc0eac64fc281414319f5aaaa2a3e76b`. I also credit its Storage Sense and duplicate-review ideas; those storage implementations are independent. Mood and negation vocabulary currently covers English and Spanish, while artist and title words work across scripts.


## Host bindings

I qualify these native interfaces against BitChord 1.9 and fail patching with a specific message when their expected shapes change.

| Feature | Binding and qualification |
| --- | --- |
| Play alongside | I wrap the audio-focus flag written in the player builder, retain the noisy-headphone flag, intercept all four player audio-attribute changes and observe explicit play requests. |
| Steady playback | I identify the service load-control construction, apply limits at its loading decision and extend its inherited permanent-aware error policy using the actual superclass and error-count field. |
| Smart downloads | I register a private JobService and qualify the host Kotlin object's download coroutine, its instance field, source identity getter and cancellable coroutine scope. |
| Sing | I intercept the native float block, qualify the sink's fields and delegate methods, wrap every output write, and drain through the host's own encoding and pending-buffer path. I also qualify the inherited PCM16 processor interface. |
| Recording verification | I intercept the player's resolving data source and use the timing pair fields present in the shrunk decoder. I check the complete file even when the player requests a bounded range, reuse sequential cache blocks and keep the same recording, duration and timeline rules. |
| Telegram handover | I locate the version-swap and upgrade commit by their behavior and diagnostic strings, qualify the coroutine constructor, capture the commit’s service before R8 reuses its register, start the silent audition from the current playing position after verification and consume the verified offset at the alignment step. |
| Source preparation | I qualify Song tagging, source ranking, resolving data sources, cache-key generation and canonical audio identity. I verify every reachable normal return executes its transform. |
| Source records | I capture the serving route, actual cache read/write and successful saved-file creation, qualify register flow, and bind Stats to the playing rendition. |
| Download settings | I locate the native download-settings block through its existing setter reference and qualify the following Compose row before adding the patch entry. |
| FLAC Downloader | I filter the playback catalogue and submitted playback search while leaving the eligible download catalogue intact. |

I retain the existing build commands and original APK hash pins. I provide conversion and installation details alongside the MPP rather than rebuilding or replacing the upstream app source.
