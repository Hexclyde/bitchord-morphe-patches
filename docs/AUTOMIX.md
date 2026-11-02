# Source-independent AutoMix

I use the **Source-independent AutoMix** patch in beta.18 to correct the source-specific analysis path in BitChord 1.9. Select it in Morphe alongside any sources and other patches you already use, then enable AutoMix in BitChord's playback settings.

## Playback and analysis

I reuse valid stored analysis for the exact rendition and use full model analysis for a fresh measurement when memory permits. On smaller heaps I use bounded DSP analysis. The DSP path keeps the native confidence rules and measures the selected recording; it supplies no invented model beats or vocal evidence. Its temporary loading allowance protects both analysis and playback without changing the saved buffer setting.

I bound DSP measurement to recordings of at most 15 minutes and sample rates up to 384 kHz. These are background-analysis limits; longer recordings and other playable formats retain ordinary playback and crossfading.

I show **Crossfade** for an ordinary volume overlap and retain **Mixing** for the native planner's qualifying mixes. I preserve explicit transition-editor settings. A low-confidence or unsupported recording can legitimately use a plain fade.

The host analyzer reads the selected audio through the same source router as playback. Telegram/add-on search rows, files, content-provider downloads, HTTP and SMB audio all enter that path. The router's native Telegram and other enabled source adapters remain responsible for opening their own files. Soulseek's download-only and isolated preview rules are unchanged; completed downloads are local audio.

An exact, complete cached rendition is read without a network fetch. Otherwise, analysis resolves and pins the selected source, including its request headers, and uses independent random-access readers. Different encodings never share a byte-cache entry. A quality/source change invalidates the previous analysis; stored analyses use a versioned rendition key. Analysis never replaces audible playback or writes a YouTube label over another playing source.

Analysis runs on the host's background executor, with thermal and party gates, bounded retries and cancellation when a rendition changes or the service stops. Until sufficient usable analysis is available, the host uses its ordinary crossfade. A format the host's analysis decoder cannot extract also uses that fade even if another playback decoder can play it. This release does not claim new decoder/DRM support.

## Source preference and identity

Enabled, permitted alternatives are considered before YouTube while retaining their relative provider order. Source enable switches and quality ceilings remain in effect. Equivalent known formats can be considered as upgrades instead of requiring the old large bitrate margin. Existing quality ranking remains responsible for choosing formats; an audio fingerprint does not certify lossless provenance or mastering quality.

Automatic resolver substitutions are checked before the replacement supplies new playback bytes. Verification compares fingerprints at the opening, middle and ending, checks container durations and requires a consistent timing offset. Explicitly picked source rows are analysed directly without a YouTube lookup. A mismatch or insufficient evidence refuses the automatic replacement; the host's existing recovery/upgrade path keeps or restores the original source. Reference checks can use YouTube audio while the selected higher-quality source supplies playback. Verified relative offsets are handed to the host's same-recording version swap.

These are conservative, multi-window recording checks, not a proof of whole-file identity, an identical master, or a guarantee that a labelled FLAC originated losslessly. Verification can add network traffic and delay an uncached automatic source choice; its decoder/read budget is bounded. First-play latency and live provider behaviour still require phone testing.

## Playback speed

AutoMix preserves the speed selected by the listener, including a deliberate setting other than 1.0×, and keeps the selected pitch. Its incoming/outgoing tempo multipliers and phase-lock speed adjustments are removed. The native loop-mix tempo-ramp path is bypassed; transitions use the host's cues, gain curves and filtering instead. Finishing, skipping or cancelling a transition therefore cannot leave a tempo adjustment behind.

Battery saver audio offload yields while AutoMix needs decoded audio, including when AutoMix is toggled during playback. Existing Listen Together and exclusive bit-perfect output restrictions remain in place.

## Installation

1. Import `bitchord-patches-1.1.0-beta.18.mpp` as a Local source in Morphe.
2. Patch an original BitChord **1.9** APK matching your architecture, keeping your existing selections and adding **Source-independent AutoMix** (selected by default).
3. Update the installed application with the same signing key. Do not uninstall or clear data.
4. Enable AutoMix in playback settings and enable the sources you want to use.
5. Check mixed queues, including Telegram → YouTube, YouTube → local/downloaded audio and local → Telegram. Check the source/format displayed and test at both 1.0× and a deliberately chosen different speed.

The source ZIP includes a Git bundle for the included changes, the complete source tree, release assets, checksums and a Windows import/publish script. Physical-phone audio and public-network verification are pending; APK and framework tests are not a substitute for those checks. The native visual transition editor is available through AutoMix's settings gear; see [transition editing](TRANSITION-EDITOR.md).
