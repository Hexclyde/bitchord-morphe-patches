# 1.1.0-beta.6

Download and playback-tweak controls now live under **Settings → Patches &
tweaks**, including Soulseek downloads, Peer downloads and Debrid downloads.
The Soulseek setup row is removed from Sources while its internal search and
download registration stays available. Unofficial Telegram keeps its existing
settings, source row and behavior.

Each feature on Patches & tweaks has an enable switch and BitChord's settings gear.
Disabled features hide their detailed controls and disable the gear. Enabling
one expands its controls; opening another collapses the previous details while
keeping its on/off state. The gear reopens an enabled feature, and saved settings
are retained when it is disabled.

The download pages now follow BitChord's fonts, colors, rounded groups and
navigation. The audio format chooser uses numbered rows, enable switches and
drag handles to set preference order. Accessible move actions offer another way
to reorder the list. At least one format must stay enabled, and choices are
saved explicitly.

The existing Phone/Telegram/Both destinations, verified original-file downloads,
paired transports and four optional debrid providers remain available. This is a
testing build for BitChord 1.8.1 on Android 8 or newer. Local UI and APK checks do
not establish physical-phone gestures, accessibility, Android background behavior
or live provider/public-network operation. See the [testing guide](SOULSEEK-TESTING.md).
