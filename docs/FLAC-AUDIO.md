# Flac Downloader Audio

Select **Flac Downloader Audio** in Morphe when patching original BitChord 1.9. It is optional and starts disabled. Enable it in **Settings → Sources** or **Settings → Patches & tweaks**. Both switches use the same saved source state; this toggle has no settings gear.

I use this provider only for downloads. It is excluded from playback, AutoMix, quality upgrades and the player’s add-on search. Downloaded files remain playable from the library. With **Unofficial Soulseek audio** also installed, the Downloads explorer and batch review include this source alongside every other eligible enabled addon. Without that download-manager patch, BitChord's existing download action remains available and the extra Downloads navigation icon stays absent.

The current provider offers **FLAC**, **MP3 320 kbps** and **MP3 128 kbps**. Explorer shows these formats without inventing FLAC sample rates or bit depths. Availability is confirmed during preparation; a failed or downgraded format never silently substitutes another quality. Low-quality searches request MP3 128, high-quality searches MP3 320, and lossless searches include the provider's explicit choices.

The service now requires its own browser verification. Use **Provider verification** on the enabled feature card, or choose a FLAC Downloader result in Explorer. Complete verification on the official provider page, then choose **Continue**. The page itself owns verification. The app does not solve its challenge. Continuing verifies and stores the provider session in Android-keystore-protected storage; it does not start a download. Return to Explorer and explicitly choose **Now** or **Later**. An expired session asks for verification again.

Each selected source ID retains its catalogue identity and requested format. New searches use the provider's current catalogue and server preparation flow: search → verified preparation token → one download job → bounded status polling → returned file. Old saved Qobuz references remain supported through the legacy route, subject to provider availability. Search and preparation run only when this source is enabled and used. The adapter does not send BitChord, Spotify, Qobuz or Telegram account cookies.

The adapter bounds response sizes, timeouts, polling and source-disable/interruption checks. It validates provider URLs and filenames, and rejects a reported quality downgrade. FLAC bit depth, sample rate and bitrate are not inferred from the requested format. Actual decoded file details remain the player's responsibility.

## Upstream reference and scope

This is an independently written Java adapter for BitChord's native addon client. It does not load a Spotube `.smplug`, embed Hetu, copy the plugin's title-matching/fallback implementation, or depend on a Spotube installation.

Protocol reference: [aztecempire/spotube-plugin-flac-audio](https://github.com/aztecempire/spotube-plugin-flac-audio), plugin 2.1.0, inspected at commit [`4ecc535d57c32fe347a301e8fc54ce9be0430158`](https://github.com/aztecempire/spotube-plugin-flac-audio/tree/4ecc535d57c32fe347a301e8fc54ce9be0430158). Its README describes MIT licensing, but that pinned tree has no licence file. No upstream source or logo is bundled. This repository's independent adapter follows this repository's GPL-3.0-or-later licence.

Automated tests exercise protocol fixtures and patched APK structure. They do not establish current service availability, catalogue coverage, actual recording quality, background transfer behavior or playback on a phone. The service uses an internal API that may change independently of BitChord.
