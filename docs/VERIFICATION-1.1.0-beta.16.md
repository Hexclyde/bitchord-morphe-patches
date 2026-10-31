# beta.16 verification

The source descends from the combined beta.15 (`36c1227896e65ddacbe53720d381b05110436752`), retaining both beta.14 tips, beta.13 and the beta.11 guide history. The package's verification.json records the final commit and APK hashes.

- 187 Android framework tests, including two new Telegram responsiveness tests, four actual-player-view overlay tests and six Downloads layout/view tests. Existing preview, archive, playlist, toggle, capture and startup tests remain.
- Telegram tests block native preparation or saved-index reads deliberately, verify the page is already painted and usable, reject index reads on the main thread and verify the cached status updates after release. These reproduce the blocking code path; they do not measure the reported phone's latency.
- Player tests cover a missing animation window, a stale window behind the foreground player, content replacement, closed players and non-YouTube tracks. Existing tests verify sticky mode and version handling. APK inspection checks that the content hook is inside the called NowPlayingScreen restart scope and that the controller hook is on a called host method.
- Downloads tests cover each saved view, card geometry, 3 dp progress from actual bytes, compact overflow controls and absence of the fake sharing row. Five PNGs render production Android views at 393 × 852 with controlled demo files. They show the Downloads content, not an installed full app or a live backend.
- 31 Python tests, the complete host suite including 384 selected-download checks, repository/link checks and Java/Kotlin/Python formatting.
- Nine patched APK selections: all patches on arm64-v8a, armeabi-v7a and x86_64; playback-only arm64; Streamseek with Soulseek; Soulseek without previews; FLAC Audio alone; mixed playlists alone; Telegram with clone only. Inspections include DEX flow, host reflection ABI, restart scopes, conditional navigation, header binding, credential protection, optional-runtime isolation, signatures and native architectures.
- All 27 native libraries are byte-identical to beta.13. No native source changed; Rust/native loopback tests were not rerun.
- All 14 original guide files and all six badge URLs are preserved. Local guide references and JavaScript syntax pass. The ZIP includes the full README, INSTALL.md and Open-Guide.html. No fresh browser interaction pass is claimed.

The recording shows a delay before Telegram appears. Inspection found synchronous saved-index SQL counts on the opening UI path; those calls now run in the background. Native bridge preparation also runs off-main. This establishes a corrected blocking path, not a trace-based attribution of every second of delay on the user's phone.

No physical device, real Telegram account, public Soulseek peer, live FLAC service or Android background-playback qualification is claimed. Repatch original BitChord 1.9 with the same Morphe signing key and update the current installation. No data-clearing step is required. Phone retesting is still needed for final shell placement, opening latency and real service behavior.

Nothing is pushed or published to GitHub during package preparation.
