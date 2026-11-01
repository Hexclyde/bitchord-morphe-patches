# Streamseek previews

This optional Morphe patch adds explicit previews of selected Soulseek audio files, including high-resolution lossless formats to BitChord 1.9. It depends on **Unofficial Soulseek audio** and starts disabled in the app. Its Patches & tweaks row uses the native capsule switch without a configuration gear. Enable Soulseek, then this switch, to reveal inline preview controls in Soulseek search and Download Explorer.

## Listen, then keep

Select a specific result and press its inline Play button. The card shows actual playback time, buffered bytes and decoded format, with Pause/Resume and Stop. Rendering or selecting a result does not start a transfer. A separate Media3 player reads the growing native transfer, requests audio focus and handles headphones becoming disconnected. Playback stops at 30 seconds; hiding the selected card or leaving the page stops the preview. It does not replace the host playlist or automatically resume the previous player.

The selected card's **Now** or **Later** action retains that exact peer/file transfer before the preview releases it. The optional recording-checks panel also retains **Keep download now** and **Add to download queue**. Downloads tracks the selected download using the existing destination, format and queue policies. A rejected selection is reported; there is no silent peer or format substitution.

The preview is temporary, but it uses real network data and disk space. A fast peer can send the complete file before 30 seconds of listening. There is one preview session, a five-minute session expiry, a 64 MiB received-data stop threshold checked once a second, and the existing 256 MiB per-file admission limit and free-space reservation. The threshold is not an exact bandwidth cap. Pause stops playback while the transfer can continue. Stop/close releases preview ownership; an unretained temporary file is removed when its last reader closes. A retained download continues under Downloads instead.

The screen does not offer an unrestricted seek slider. When Media3 requests a seek, its loading thread reopens the same selected buffered file and discards decoded samples up to the requested position. This avoids depending on remote seek support or a complete container index. Reads still wait for the contiguous received prefix; cancelled, missing or truncated data is never treated as playable silence. Containers with metadata at the end (for example, some M4A files) may need substantial buffering before playback. Unsupported codecs fail explicitly.

## Check the recording

Open **Recording match details** beside the inline player for the compact native
recording-checks panel. Its reference and evidence tools remain optional; opening
it stops inline playback and does not start a new preview or download.

1. Choose a known **local reference audio file**, then restart the preview. The reference uses up to its first 45 seconds. This build does not capture YouTube playback or query AcoustID automatically.
2. Compare audio excerpt independently decodes up to the candidate's first 25 seconds, before playback EQ/pitch processing. It needs at least ten seconds of usable audio and sufficient landmarks.
3. Read the excerpt result, seconds checked, aligned/candidate landmark counts and reference offset. A matching excerpt is evidence about those excerpts. Different intros, edits, silence or a match outside the reference window can prevent a match. A negative or inconclusive result is not proof of a different recording.
4. Inspect complete-file checksum once the entire selected file is buffered. SHA-256 identifies those bytes; no trusted reference checksum is automatically compared. **Confirm correct recording** records the user's judgment for that complete hash. It remains separate from algorithmic evidence.
5. Export match report saves JSON through Android's file picker. It includes the selected peer/path, declared format, received-byte count, available header and excerpt evidence, full-file checksum when present, and user confirmation. It excludes credentials and the app's private cache path.

FLAC STREAMINFO reports header bit depth, sample rate and channels. Neither those fields nor a fingerprint certify lossless origin, the mastering, the complete recording or a “perfect match.” JSON explicitly keeps those verification claims false. A partial preview cannot establish complete-file identity. Reports are user-exported evidence, not signed attestations.

## Formats and decoding

Streamseek uses a separate on-device FFmpeg 7.1.5 demuxer/decoder, selected only
with this optional patch. It has no network protocols and receives bytes only
through the selected-file preview capability. It does not depend on the host's
FFmpeg renderer. The runtime is built for arm64-v8a, armeabi-v7a and x86_64 with
Android API 26 and 16 KiB ELF alignment.

Supported decoder/container paths include FLAC (including Ogg FLAC), integer or
floating-point PCM WAV/RF64/Wave64 and AIFF/AIFF-C/CAF, ALAC in M4A/MP4/CAF,
Monkey's Audio, WavPack, True Audio, TAK, Shorten, WMA Lossless, DSF/DSDIFF
(including DST), DTS/DTS-HD and TrueHD/MLP. Matroska audio can carry enabled
codecs. MP3 and AAC/Vorbis/Opus container paths remain compatible where supported
by the decoder. An extension is only an admission hint: ambiguous M4A, Ogg,
Matroska, CAF and WMA names never establish lossless provenance.

The decoder feeds 32-bit PCM at its decoded sample rate into Media3, with the
high-resolution output path enabled. Integer 24-bit and 32-bit fixture samples
are preserved in the decoder output. Decoded sample counts provide the preview
clock, including containers with coarse or missing packet timestamps; large
codec frames are trimmed at the 30-second boundary. DSD is converted to PCM; this is not native
DSD output. Android, the audio route and the connected hardware still determine
the actual output rate and precision. Preview decoding is not a bit-perfect
output guarantee and does not certify source quality.

Existing saved format priorities and enabled choices are retained. New format
choices are appended disabled for existing configured users; enable them in
Audio formats when wanted. New installations include them in the default list.
Opening settings never writes that migration back automatically.

FFmpeg's exact unmodified source archive, adapter, notices and offline build
script are included under `native/preview` and `tools/build_preview_native.py`.
The normal MPP build workflow can recover the pinned decoder binaries from the
release bundle through the existing bootstrap process. No decoder is loaded
when simply opening settings or searching.

Local-reference recording comparison still uses Android's platform decoding
path. Its codec support can be narrower than preview playback; unsupported
comparison is reported without asserting a match.

## Implementation and qualification

The design is inspired by [ndr-brt/streamseek](https://github.com/ndr-brt/streamseek), reviewed at commit `851efcfcbf66e8b3216191d56616af378e9573b0`. This is an independent Android implementation over the existing native bridge. It does not bundle or run Streamseek's Node/Electron server or copy its source. It does not treat a nonempty partial cache file as complete.

Automated checks cover capability binding, growing-file reads, cancellation,
queue retention, evidence wording, checksum binding, fingerprint alignment,
format-choice persistence, optional-patch isolation and the exact Media3 API in
patched APKs. `tests/streamseek-native/run.py` tests the installed production JNI
decoder on a device with generated audio and reference PCM. These are decoder
fixture tests, separate from live remote-peer and audible playback tests. The
beta.17 verification report records the exact tested formats and limitations.
Physical phones, all remote codecs/peers and every audio route require further
qualification.
