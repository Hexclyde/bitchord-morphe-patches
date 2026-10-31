# beta.14 verification

- Build: BitChord 1.9 / Morphe patch bundle 1.1.0-beta.14; all 18 patches retained.
- 169 Android framework tests passed, including null/default modifier behavior, explicit modifier retention, window open/close/recreation, duplicate-overlay prevention and external-track visibility.
- 31 Python tests and the complete host regression (`python3 test.py`) passed.
- Four APK cases passed: combined arm64-v8a, armeabi-v7a and x86_64; playback-only arm64-v8a. Combined cases verify 391 Downloads integration assertions, including placement inside the host restart group. All player cases verify hooks after both actual optimized player-window writes.
- DEX control-flow, APK signatures, native architecture/dependency checks and release metadata checks passed. All 27 native libraries are byte-identical to beta.13.
- Formatting checks passed for changed Java/Kotlin files and build/validation Python scripts.

Framework tests use Android through Robolectric with explicit host ABI fixtures; APK inspection checks the optimized host bytecode. These checks do not constitute execution of the whole patched app on a physical device. The supplied crash text confirms the embedding entry but omits the nested cause. The corrected null modifier and restart-scope problems are independently established from the original APK and patch code.

Phone retest: open Downloads, enter Explorer, return with Back, and switch between Downloads/Library/Search. Enable Audio and video switch, load a YouTube Music track, open the full player, close it and reopen it several times. Confirm the control remains available and switches versions. Local, Telegram and addon tracks intentionally hide the control.

No GitHub publication or live account/network qualification was performed.
