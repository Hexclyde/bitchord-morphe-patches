# Playback, downloads and storage

Play alongside other apps, Steady playback, Smart downloads and Karaoke start disabled. Source-independent AutoMix is selected by default in Morphe and uses BitChord’s AutoMix setting. Open **Settings → Patches & tweaks** to enable a feature and its gear to change its settings. Controls follow BitChord’s theme, text size and green capsule switches.

## Playback

**Play alongside other apps** continues music over other apps’ audio and pauses for ringing, phone and communication modes. Recovery applies to the same track; changing the track, pausing manually or disabling the feature cancels it. Headphone-disconnection handling also applies.

**Steady playback** offers 16/32/64/96 MiB read-ahead on unmetered connections and 8/16/32/64 MiB on metered connections. These are upper limits: combined music, spare-player and animated-artwork buffers also depend on available memory. Disabling the feature restores BitChord’s buffer policy without resetting its settings. Retry counts increase while the network is absent; online errors follow BitChord’s policy.

**Smart downloads** considers songs with at least three plays in the last three months. It searches enabled download sources, matches recordings and ranks copies in the selected Download quality. Each transfer uses its chosen source and format. Saved or previously handled songs are skipped, including downloads removed by the listener. Failed songs can retry.

Android schedules up to 25 songs about twice daily on an unmetered network, with charging required by default and a free-space guard. Disabling the feature stops its work without cancelling unrelated transfers. It never deletes music automatically.

## Prepared sources and cache identity

Enable **Play the best source first** in Unofficial Telegram settings to prepare the next two queued songs through eligible enabled sources. Recording checks, source selections, quality limits and delivery speed govern the choice. YouTube supplies playback when no verified alternative is ready or delivery is too slow.

Whole-track preparation compares opening, middle and ending fingerprints with a 0.75-second duration limit. File durations can explain small timing differences; unexplained residual drift is limited to 80 ms. Live swaps use a separate strict timeline check and never take an offset from whole-track preparation. Neither path changes speed or pitch.

The selected track waits at most 20 seconds for preparation. A confirmed upcoming entry outside both player decks can wait up to 60 seconds; selecting it shortens that deadline. Battery, thermal and network restrictions also apply. A queue entry’s rendition stays fixed while it plays, even when another occurrence of that song prepares a better copy. Source or setting changes cancel stale work.

A complete YouTube cache copy remains available until its verified replacement is complete. Active readers and saved downloads are protected. Each encoding has a separate cache resource, and obsolete playback cache is reclaimed only after the replacement plays reliably.

Stats, Downloads and Storage Sense identify the actual source per cached rendition or saved file. Advertised format and measured details are distinct. Unknown, old or mixed provenance stays unknown; source URLs and credentials are outside these records.

## AutoMix analysis

BitChord’s planner uses measurements from the selected recording. Full model analysis runs when its working set fits. On smaller heaps, bounded decoding produces continuously resampled 11.025 kHz mono for BitChord’s DSP features. This path supplies measured features without inventing model beat or vocal evidence.

Insufficient evidence or memory uses a basic **Crossfade**; **Mixing** identifies a qualifying native plan. Transitions respect the chosen speed, pitch, source and saved pair edits. See [AutoMix](AUTOMIX.md) and the [transition editor](TRANSITION-EDITOR.md).

## Karaoke: separation model

Enable **Karaoke** and open its gear to choose **Filter** or **Separation model** and adjust the removal level. Separation uses BitChord’s bundled Open-Unmix export. A zero level passes the original audio unchanged. Missing, late or unsupported inference uses the filter.

On the lyrics screen, Karaoke adds a microphone capsule at the bottom right, above the translation button. Tapping the microphone chooses the separation model, turns Karaoke on and opens a vertical slider: the bottom is 0%, the original vocals, and the top is 100%, the most removal. The slider and the Voice slider on the settings page are the same value. The capsule shows the model's status while it loads and when it falls back to the filter, and **Off** turns Karaoke off. The slider closes by itself after three seconds without a touch, or when the microphone is tapped again, and the microphone then fades out; tapping its place brings it back. Closing it never turns Karaoke off. Screen readers get a slider with fine steps and a reset to 50%, and the slider stays open while a screen reader or keyboard is on it.

Separation applies a soft vocal mask with 4096-sample Hann windows, a 1024-sample hop and weighted overlap-add mapped to the model’s 44.1 kHz frequency grid. Lookahead and processing add about 1.60 seconds at 44.1/48 kHz. Seeks reset its history; ordinary track boundaries preserve delayed audio. Nonstandard speed or pitch bypasses separation, and bit-perfect output bypasses the effects path.

I credit [Open-Unmix](https://github.com/sigsep/open-unmix-pytorch) by sigsep for its MIT-licensed code. BitChord’s quantized export is pinned to SHA-256 `a2be987b55a29bc149d3a6ae99b08175d81f85ee292a8ea21f96c3a473bc94cb`. The asset has no explicit export licence metadata and is supplied by BitChord rather than this patch bundle.

[convert_spleeter.py](../tools/convert_spleeter.py) converts [Spleeter](https://github.com/deezer/spleeter) weights through TensorFlow/tf2onnx to FP32 or FP16 ONNX. The conversion checks the official archive hash, tensor interface and finite output, and preserves frequencies above the model’s approximately 11 kHz band. The [Spleeter paper](https://doi.org/10.21105/joss.02154) identifies Deezer’s code and pretrained models as MIT licensed. Converted weights need local preparation; no in-app download is available.

## Downloads and Storage Sense

BitChord’s Downloads settings contain quality, Wi-Fi restriction, export location and downloaded-playlist synchronization, followed by **Patch downloads**. The same controls are accessible from Patches & tweaks.

Open **Storage Sense** directly from **Patches & tweaks → Library**, or from **Settings → Downloads → Patch downloads**. It lists cache resources with stored ranges and completeness. Telegram cache lists indexed music files. Cleanup uses the owning cache or TDLib API and protects playing, upcoming, queued, downloading and recently used resources. Locations without a safe removal path are read-only, including Soulseek’s transfer cache.

Saved-file deletion requires separate confirmation. Duplicate review compares file contents by SHA-256 with bounded, cancellable work. Names alone never justify removal. Session data, credentials, model files, unknown locations and active transfers are protected.

Age and size cleanup rules start disabled. Enabled rules run at most once daily after app start against eligible caches; opening the page never cleans anything. See [known storage limits](KNOWN_LIMITATIONS.md#downloads-and-storage).

## Smart playlists

Smart playlists uses songs, playlists, likes and listening metadata on the device. Describe an artist, title, genre, mood, decade or tempo, inspect the matching songs and their reasons, then save. Sparse metadata can produce a short result; songs without supporting tags appear separately when explicitly requested. Local creation sends no source search or external text request.

An optional compatible HTTPS text service can supply suggestions after explicit setup and selection. Its key uses encrypted local storage. **Generate** sends the description, never audio; actual source matches still require review before saving. Leaving the page or cancelling prevents stale results from appearing.

I credit [PixelMusic](https://github.com/ianshulyadav/PixelMusic) by ianshulyadav (Anshul Yadav, GPL-3.0) for local playlist language processing and ranking, adapted at commit `991f7c5abc0eac64fc281414319f5aaaa2a3e76b`, and for Storage Sense and duplicate-review ideas. Mood and negation vocabulary covers English and Spanish; artist and title words match across scripts.
