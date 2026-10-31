# Streamseek previews

This optional Morphe patch adds explicit previews of selected Soulseek MP3 and FLAC files to BitChord 1.9. It depends on **Unofficial Soulseek audio** and starts disabled in the app. Its Patches & tweaks row uses the native capsule switch without a configuration gear. Enable Soulseek, then this switch, to reveal Preview actions in Soulseek search and Download Explorer.

## Listen, then keep

Choose Preview on a specific result, review its peer and filename, and press Start preview. A separate Media3 player reads the growing native transfer. It requests audio focus and handles headphones becoming disconnected. Playback stops at 30 seconds; leaving the screen stops the preview. It does not replace the host playlist or automatically resume the previous player.

**Keep download now** or **Add to download queue** retains that same peer/file transfer before the preview releases it. Downloads tracks the selected download using the existing destination, format and queue policies. A rejected selection is reported; there is no silent peer or format substitution.

The preview is temporary, but it uses real network data and disk space. A fast peer can send the complete file before 30 seconds of listening. There is one preview session, a five-minute session expiry, a 64 MiB received-data stop threshold checked once a second, and the existing 256 MiB per-file admission limit and free-space reservation. The threshold is not an exact bandwidth cap. Pause stops playback while the transfer can continue. Stop/close releases preview ownership; an unretained temporary file is removed when its last reader closes. A retained download continues under Downloads instead.

Seeking is limited to received bytes; the screen does not offer an unrestricted seek slider. Unsupported files, unavailable peers, queue waits, account changes, disabled settings and decoder errors fail visibly. Arbitrary Soulseek source URLs still cannot start playback. Non-exported activities accept expiring capabilities bound to the selected peer, path, size and source session.

## Check the recording

1. Choose a known **local reference audio file**, then restart the preview. The reference uses up to its first 45 seconds. This build does not capture YouTube playback or query AcoustID automatically.
2. Compare audio excerpt independently decodes up to the candidate's first 25 seconds, before playback EQ/pitch processing. It needs at least ten seconds of usable audio and sufficient landmarks.
3. Read the excerpt result, seconds checked, aligned/candidate landmark counts and reference offset. A matching excerpt is evidence about those excerpts. Different intros, edits, silence or a match outside the reference window can prevent a match. A negative or inconclusive result is not proof of a different recording.
4. Inspect complete-file checksum once the entire selected file is buffered. SHA-256 identifies those bytes; no trusted reference checksum is automatically compared. **Confirm correct recording** records the user's judgment for that complete hash. It remains separate from algorithmic evidence.
5. Export match report saves JSON through Android's file picker. It includes the selected peer/path, declared format, received-byte count, available header and excerpt evidence, full-file checksum when present, and user confirmation. It excludes credentials and the app's private cache path.

FLAC STREAMINFO reports header bit depth, sample rate and channels. Neither those fields nor a fingerprint certify lossless origin, the mastering, the complete recording or a “perfect match.” JSON explicitly keeps those verification claims false. A partial preview cannot establish complete-file identity. Reports are user-exported evidence, not signed attestations.

## Implementation and qualification

The design is inspired by [ndr-brt/streamseek](https://github.com/ndr-brt/streamseek), reviewed at commit `851efcfcbf66e8b3216191d56616af378e9573b0`. This is an independent Android implementation over the existing native bridge. It does not bundle or run Streamseek's Node/Electron server or copy its source. It does not treat a nonempty partial cache file as complete.

Automated checks cover capability binding, growing-file reads, cancellation, queue retention, evidence wording, checksum binding, fingerprint alignment, optional-patch isolation and the exact Media3 API in patched APKs. Real-phone playback, audio focus interaction, Android background/service behavior and real remote peers still need device qualification. No live-device or live-peer test is claimed.
