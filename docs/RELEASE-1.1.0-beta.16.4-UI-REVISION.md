# 1.1.0-beta.16.4 — combined UI and playlist fixes

Download Explorer now follows the supplied reference: rounded All/Lossless/Available now pills, a selector fixed in each card’s top-right corner and Now/Later capsules in only the selected card, beneath a full-width divider. There is no fixed admission footer. Available now is kept only when a source reports readiness; it filters Soulseek peers reporting a free upload slot. Unknown add-on readiness stays under All.

Device playlist creation now calls the public methods on the actual optimized flow instance. BitChord 1.9 removes them from MutableStateFlow’s interface; the old lookup caused the generic playlist-update toast. Saved files, stream identities, native playlists and preferences are preserved. Regression coverage includes the creation dialog, mixed membership and restart restore.


The Audio/Video switch is now part of the player layout. Its portrait row sits after the native drag handle/origin caption and before the weighted artwork. The landscape main pane has its own row. The native player measures and moves these views; no separate Window child, fixed status-bar offset or elevation tracker is used.

Headphone and video icons replace the visible text. Each has a 52 × 48 dp touch target, tooltip, accessible label and selected state. Native player tint follows the artwork. The saved YouTube playback preference remains persistent. Disabling the patch or playing an external/local file removes its layout height. Dragging the player retains the row's geometry.

All 19 patches and beta.16.3 behavior remain, including Streamseek, Explorer, local downloads search, Telegram phone correction/resend and MicroG account contrast. No GitHub publication is performed when preparing this package. Live-phone placement and actual YouTube version availability still require device testing.

This release advances the combined beta.16.3 UI revision (c5deba1) to version 1.1.0-beta.16.4. It retains every Explorer, playlist and player fix from that revision. The MPP manifest, runtime labels, source metadata, guide and demo use beta.16.4. Import the included local MPP to test it. No GitHub publication is performed when preparing this package.
