# BitChord patches 1.1.0-beta.17

- Add **Source-independent AutoMix**, selected by default when patching original BitChord 1.9. Analyse the actual selected source rather than excluding picked add-on rows or requiring a matched YouTube analysis copy.
- Reuse complete caches, pin independent source readers and isolate stored analyses by rendition. Cancel stale jobs and keep playback source diagnostics separate from reference/analysis reads.
- Consider enabled alternative providers before YouTube. Add conservative three-window recording/timeline verification at the playback resolver boundary and pass verified relative offsets to same-recording swaps.
- Preserve the listener's playback speed and pitch through every transition speed path. Bypass native tempo-ramping loop mixes and retain cue/fade/filter transitions.
- Make Battery saver audio offload yield to AutoMix's processing requirements.
- Retain beta.16.4's player icon controls and all 19 existing patches. This build adds a twentieth patch and does not include the visual transition editor.

See [AutoMix behaviour and installation](AUTOMIX.md). Framework, host and APK verification coverage is recorded in the package verification report. Live phone audio, network/provider latency and subjective transition quality still require testing. This is a testing build, not a claim of device validation.
