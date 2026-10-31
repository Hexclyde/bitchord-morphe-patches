# Flac Downloader Audio

Select **Flac Downloader Audio** in Morphe when patching original BitChord 1.9. It is optional and starts disabled. Enable it in **Settings → Sources** or **Settings → Patches & tweaks**. Both switches use the same saved source state; this toggle has no settings gear.

Search through **Search → Addons**. With **Unofficial Soulseek audio** also installed, the Downloads explorer and batch review include this source alongside every other eligible enabled addon. Without that download-manager patch, BitChord's existing download action remains available and the extra Downloads navigation icon stays absent.

The adapter offers explicit requested renditions: FLAC 24-bit/192 kHz, FLAC 24-bit/96 kHz, FLAC 16-bit/44.1 kHz and MP3 320 kbps. These are preparation requests, not claims that every recording has every rendition. The explorer labels them as requested and unverified. A failed selected rendition does not silently change format or recording. Choose another source or format yourself. MP3 128 kbps is not supported by this service adapter; low-quality streaming searches return no candidates.

Each selected source ID contains its catalogue identity and requested format. It remains resolvable after restarting, including inside a mixed-source device playlist. Search queries and selected catalogue identifiers are sent to **flacdownloader.com** only when this source is enabled and used. The adapter does not send a BitChord, Spotify, Qobuz or Telegram account cookie. Preparation tokens remain in memory for that request.

The observed service protocol is search → prepare token → one queue request → bounded job polling → returned file. The adapter adds request timeouts, a one-second polling interval, response-size limits, source-disable/interruption checks, strict internal routing and filename validation. It does not synthesize FLAC bit depth, sample rate or bitrate from a requested format. Actual decoded audio details remain the player's responsibility.

## Upstream reference and scope

This is an independently written Java adapter for BitChord's native addon client. It does not load a Spotube `.smplug`, embed Hetu, copy the plugin's title-matching/fallback implementation, or depend on a Spotube installation.

Protocol reference: [aztecempire/spotube-plugin-flac-audio](https://github.com/aztecempire/spotube-plugin-flac-audio), plugin 2.1.0, inspected at commit [`4ecc535d57c32fe347a301e8fc54ce9be0430158`](https://github.com/aztecempire/spotube-plugin-flac-audio/tree/4ecc535d57c32fe347a301e8fc54ce9be0430158). Its README describes MIT licensing, but that pinned tree has no licence file. No upstream source or logo is bundled. This repository's independent adapter follows this repository's GPL-3.0-or-later licence.

Automated tests exercise protocol fixtures and patched APK structure. They do not establish current service availability, catalogue coverage, actual recording quality, background transfer behavior or playback on a phone. The service uses an internal API that may change independently of BitChord.
