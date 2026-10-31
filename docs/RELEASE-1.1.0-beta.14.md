# 1.1.0-beta.14 testing build

Fixes two UI regressions reported on BitChord 1.9:

- Downloads passed the host's R8-optimized null modifier directly into AndroidView. Request its default modifier for null input. Keep page rendering and navigation-state reads inside LibraryScreen's existing restart group, using its original exit and recomposition callback.
- The song/video control attached from the player-sheet constructor only. Observe every actual write to the player window, including inlined host code, so the control is added to each newly opened window and removed when it closes.

All 18 patches and the beta.13 native libraries are retained unchanged. No app data migration or reinstall is required by these fixes. Repatch the original, unmodified BitChord 1.9 APK with the new MPP and the same selections, then update the existing patched installation using the same Morphe signing key. Keep Audio and video switch enabled in Patches & tweaks. The control applies to YouTube Music tracks and remains hidden for local/Telegram/addon tracks.

See VERIFICATION-1.1.0-beta.14.md for checks and limitations. Phone playback remains unverified; no claim of live device qualification is made. Nothing is published to GitHub by preparing this package.
