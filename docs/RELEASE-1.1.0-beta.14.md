# 1.1.0-beta.14 testing build

Retains the complete beta.13 source and adds **Streamseek previews**, the nineteenth selectable patch. It depends on the Soulseek download patch and is initially disabled in the app. Its enable/disable row uses BitChord 1.9's capsule switch without a configuration gear.

- Explicit MP3/FLAC previews in Soulseek search and Download Explorer, using an isolated Media3 player and the selected peer's growing temporary file.
- Thirty-second playback, explicit stop, session expiry and temporary-data threshold. Keep download now or queue it while retaining the same transfer.
- Local-reference excerpt comparison with decoded PCM, landmark counts, checked duration and alignment offset. FLAC header inspection, complete-file SHA-256, user confirmation bound to that hash, and JSON export.
- Ordinary Soulseek playback remains guarded. Excerpt matches and header quality are not presented as proof of a perfect file, mastering or lossless origin. YouTube reference capture and Mornye integration are not included.

See [Streamseek behavior and limits](STREAMSEEK-PREVIEW.md) and [publishing instructions](PUBLISHING-1.1.0-beta.14.md). Automated evidence is recorded in the package's verification.json. Phone playback and live remote Soulseek peers still require device qualification.
