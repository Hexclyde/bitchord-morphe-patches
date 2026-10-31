# 1.1.0-beta.16 testing build

Fixes the production UI and opening paths exposed by the beta.15 recordings, retaining all 19 patches and complete earlier history.

- Downloads follows the reviewed demo with a compact header, real artwork when available, source/format labels, separate status lines and thin progress from actual transfer data. Grid, List, Compact, Comfy and Detailed remain saved choices. Management controls move under its settings gear. Saved host aliases are deduplicated; public-sharing settings no longer masquerade as a downloaded file.
- Downloads owns its header while visible, avoiding a duplicate host profile bar. The native player and bottom navigation remain in the shell. The Downloads icon is installed only with the download add-on.
- Audio/Video now uses text segments and attaches to the actual NowPlayingScreen view. It no longer relies on PlayerSheetMotion.window, which the host does not attach under reduced motion. View replacement and detach/reattach are handled. The saved YouTube preference remains unchanged across tracks and restarts.
- Unofficial Telegram paints a lightweight opening page before bridge preparation. Its saved-index count is no longer queried on the main thread during opening; it is cached and refreshed on a dedicated background worker. Repeated taps cannot launch duplicate setup pages. Existing startup diagnostics and credential protection remain.
- Keep the beta.11 guide and six README badges, Streamseek MP3/FLAC previews and evidence, mixed playlists, FLAC Audio, batch selection, public Soulseek uploads and verified Telegram offloading.

See verification.json and docs/VERIFICATION-1.1.0-beta.16.md for automated results and limits. The ZIP includes five renders of production Android Downloads views with controlled demo data. These are not screenshots of a live installed app.

Repatch original BitChord 1.9 with your existing Morphe signing key and patch selections, then update the installed app. No data-clearing or reinstall step is required. Keep Audio and video switch enabled in Patches & tweaks and select Streamseek previews if used.

Real-phone opening latency, full-player placement and public services still require device retesting. No physical-device, real Telegram account or public Soulseek peer qualification is claimed. No GitHub push or release is performed by preparing this package.
