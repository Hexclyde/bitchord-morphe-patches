# 1.1.0-beta.13 testing build

Adds two optional patches to the complete beta.12 build:

- **Flac Downloader Audio**: an independent BitChord adapter for the service protocol used by aztecempire's Spotube add-on. Enable its native source toggle to search/stream; with the Downloads add-on installed, review explicit requested FLAC/MP3 renditions in Explorer and batch review.
- **Mixed-source playlists**: device playlists in the existing Library shelf and player, holding local music, downloads, YouTube tracks and addon streams. Source/file identities survive restarts.

Both patches are off until chosen in Morphe and enabled in the patched app. They use BitChord 1.9 capsule switches and have no configuration gear. All beta.9–12 Downloads, sharing, Telegram archive, layout, player-mode and interactive guide changes are retained. The guide's patch catalogue now includes all 18 patches.

Validation: 165 Android framework tests, 31 Python tests, the full host regression suite and six patched APK cases (all three combined ABIs, playback-only arm64, FLAC-only arm64 and mixed-playlist-only arm64). Standalone cases verify no Telegram/TDLib/Soulseek account implementation is pulled in. New protocol tests use fixtures; no live FLAC service or phone playback qualification is claimed.

Protocol reference is pinned to aztecempire/spotube-plugin-flac-audio commit 4ecc535d57c32fe347a301e8fc54ce9be0430158. No upstream code, .smplug, Hetu runtime or artwork is bundled. Requested format availability is unverified and a failed request does not silently choose a different rendition.

The publishing package is ready for local import and later GitHub pre-release publication. Nothing has been pushed to GitHub by this packaging step.
