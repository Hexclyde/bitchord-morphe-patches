# beta.15 verification

This release merges the Streamseek beta.14 source (`bb55715674e40706d990909464954cdf3147f032`) and the Downloads/player fixes (`72be4d638b6512cbc93ba879b5283d61e89f2473`). Both tips and the original beta.11 guide (`a831d8bbbadda2c83e7455755033eda29ded6487`) are retained as ancestors. The package's verification.json records the final source commit and APK hashes.

- 181 Android framework tests, including four Telegram startup/error tests, the eight Streamseek preview tests, Downloads null/default modifier handling and player-window recreation.
- 31 Python tests, repository/link checks and the complete host suite, including 384 selected-download checks.
- Nine APK selections: all 19 patches on arm64-v8a, armeabi-v7a and x86_64; playback-only arm64; Streamseek with its Soulseek dependency; Soulseek without previews; FLAC Audio alone; mixed playlists alone; and Telegram with the clone patch alone.
- Combined APK checks cover the balanced Downloads restart scope, both actual player-window writes, Media3 reflection signatures, Telegram exception boundaries, permission declarations, DEX control flow, signatures, native ABIs and optional-runtime isolation.
- All 27 native libraries are byte-identical to beta.13. No native source changed; Rust/native loopback tests were not rerun in this environment.
- Java AOSP, Kotlin Kotlinlang and Black 26.5.1 formatting checks pass across tracked source.
- All 14 beta.11 guide files and six original badge URLs are preserved. Local guide assets exist and JavaScript syntax checks pass. The ZIP keeps the full repository README and adds Open-Guide.html plus separate INSTALL.md. A fresh browser UI run was blocked by the local-preview network policy, so no browser UI pass is claimed.

The Telegram tests establish that denied overlay protection and an uncaught startup linkage error previously closed the page. The corrected page fails before credential entry and presents diagnostics without exception messages or account/API values. A null status error is handled without a refresh crash, and an unavailable native bridge retains its readable Connection error. There was no crash trace from the reported phone, so these tests do not identify its exact cause or prove its resolution.

No physical device, real Telegram account, public Soulseek peer, live FLAC service or Android background-playback qualification is claimed. Repatch original BitChord 1.9 with the existing Morphe signing key, update the current installation and retest the reported paths. Do not clear app data as a prerequisite. If the Telegram diagnostic screen appears, Copy crash details supplies a privacy-limited trace for the next investigation.

No GitHub push, release publication or Pages configuration change is performed during package preparation.
