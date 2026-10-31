# beta.12 verification

The source compiles and packages with the pinned toolchain and retained native runtimes. Android framework tests: 150 passed. Python source/catalogue tests: 31 passed. Host suites include 23 Audio/Video policy checks and the existing download, peer, archive and codec suites.

Android framework tests cover saved Audio/Video mode after disabling/reinitialization, all five download layouts retaining every row, two-column grid composition and saved view after recreating a page. Large text falls back to a single grid column. APK verification checks both bottom navigation styles with and without the download add-on patch.

The release validation record is in verification.json in the publishing package. It lists the final MPP checksum and the APK variants checked. Native code was reused unchanged from beta.11; this merge changes Java/Kotlin UI and documentation. No new claim of public-network or physical-device qualification is made.

The HTML demo was tested for Library/Settings routing, stock versus patched navigation, all five download layouts, saved view after reload and Video preference across a track change and reload. It uses simulated music and accounts. It is a source-based HTML adaptation; native rendering, provider behavior and every host system popup are not reproduced by an Android runtime in the browser.

Phone testing remains required for exact visual comparisons, YouTube alternate-version resolution, background playback, JamesDSP/Shizuku interactions, public Soulseek peers and Telegram remote read-back/removal/restore.
