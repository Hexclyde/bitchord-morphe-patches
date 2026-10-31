# Known limitations

## Downloads in beta.11

The four implementation stages from beta.10 are implemented. The [Downloads guide](DOWNLOAD-MANAGER.md)
explains the qualified 1.9 player slot, 200-track batch limit, explicit public-share
inventory and verified Telegram archive/removal/restore flow. Quality claims remain
reported metadata; initial source plans/queues do not survive process death. Public
sharing pauses after a restart. Live Android/Waydroid and real-account qualification
is still required. The older paragraphs below describe the earlier feature boundaries.


These apply to the current release on BitChord 1.9. This version hasn't been widely tested on real phones yet, so behavior may differ between devices.

The Telegram integration was tested on an arm64-v8a device with BitChord 1.8.1; the 1.9 build still needs on-device testing. The armeabi-v7a and x86_64 builds pass the same automated checks but haven't been tried on real hardware. If you run one of them, please [report what you find](../CONTRIBUTING.md).

## Search and playback

- Regular BitChord search may still play from YouTube. Use **Search → Addons** to get Telegram results.
- A search returns up to 200 tracks across your selected chats.
- Only one Telegram account is supported. You can't create a new account from the app, and some login methods aren't supported.
- Only the standard 1.9 APKs are supported. TV, Wear OS, split APKs and other BitChord versions are not.
- BitChord plays addon sources, Telegram included, only when its streaming quality for the connection in use is **Lossless**. On High or lower, Telegram results are marked as needing Lossless. This is BitChord's own quality rule.
- Songs on BitChord's bit-perfect output skip every effect, so Sing, Pitch control, Loudness level, Headphone correction, the sleep fade and JamesDSP don't reach them.
- The JamesDSP page can't tell for certain that JamesDSP is processing BitChord. Android only says that some app is capturing the sound that plays, not which one, so a screen recorder looks the same.
- **Start JamesDSP** works without opening JamesDSP only after its optional auto-start permission (the third ADB command) is granted. Otherwise open JamesDSP and tap its power button.
- The audio and video switch shows only for YouTube Music songs. Files, addons and Telegram tracks have no video version. It hasn't been tried on a phone yet; the song menu's own switch keeps working either way.

- Copying into your main channel oldest post first starts from a community channel only once the saved index has gone through all of it, which takes about an hour for a channel of 200,000 files. **Newest first** starts straight away.
- The fake lossless check reads FLAC and WAV files, alone, in zips that aren't compressed, or as cue albums. ALAC, AIFF, APE and WavPack files aren't checked. Files made from 256 and 320 kbps MP3s usually can't be told from CD-era masters, and are called inconclusive. An old recording that never had much above 16 kHz can show **maybe lossy**.
- Naming by sound needs your own AcoustID key and finds only recordings AcoustID knows. Songs in zips and cue albums aren't looked up.
- Cue sheet tracks are handed to BitChord as uncompressed WAV, so a downloaded track takes more space than the FLAC would. APE and WavPack albums with a cue sheet aren't supported.
- Albums Telegram posted together are listed from the chat's newest 5,000 files.
- Dolby TrueHD Atmos is recognised in .mka files only. Dolby AC-4 files only play on phones with an AC-4 decoder. Dolby Atmos plays with its height sound only on phones and speakers that support it.
- The patch pages are translated into 20 languages. Error messages that Telegram itself sends stay in English.

## Channel View

- Only selected, accessible channels without content protection can be opened.
- Telegram links and channel videos open in the Telegram app, not inside BitChord.
- Some formatting, custom emoji and link previews in sponsored messages aren't rendered yet.

## Saved files

- Protected, secret and self-destructing media can't be played.
- Audio already in BitChord's cache or downloads stays playable after you sign out or remove a chat. Clear those copies separately.

BitChord 1.9 bit-perfect output bypasses audio effects, including the sleep fade.
Battery saver stays off for bit-perfect output and Automix. Telegram playback
follows BitChord's Lossless streaming-quality requirement for addons.

## Soulseek and compatible-peer discovery

The beta.9 source choice applies to eligible manual single-track Download
actions. Album/playlist loops, download retries and cached-folder exports retain
their existing paths; explicit addon and local-file selections retain their
original source. Choosing Soulseek requires reviewing and selecting a search
result. Search cancellation, no results or failure does not silently download
from YouTube. An imported Soulseek file is tracked independently and does not
mark an unrelated original YouTube row as saved. This remains a download feature,
not live Soulseek playback or automatic recording replacement.

Beta.9 returns a bounded initial Soulseek shortlist and may finish before later
search replies arrive. Repeating a query can reuse a 30-second session/policy
cache. A peer's advertised speed and free slots are unverified; availability and
queue permission are still determined by the transfer engine.

Opt-in public discovery and PEX find compatible app public keys. They do not
find ordinary Soulseek clients, search a BitTorrent DHT, join torrent swarms or
prove that a provider is reachable or has a requested recording. A public profile
is a signed, expiring capability announcement, not a live connectivity check.
Only users who explicitly advertise can be found through that mechanism. The
number of results depends on participating clients and relay acceptance; there
is no guarantee of instantly finding many peers.

The app retains at most 512 profiles, each valid for no more than five minutes.
Scans are explicit and read for ten seconds; publishing your own profile requires
a separate opt-in while the discovery screen remains open. Already paired peers
can exchange bounded batches of these signed public profiles. Nothing in the
candidate list adds trust or grants file access. Automatic recording matching,
automatic file sessions and downloading chunks from multiple providers are not
implemented. Debrid services continue to manage their own cloud transfers.
## beta.13 additions

- Flac Downloader Audio uses flacdownloader.com's internal service API. Protocol fixtures and APK structure are tested; live availability and returned recording quality are not verified. Requested renditions may fail. The adapter does not silently downgrade them.
- Mixed-source device playlists need the patch to preserve their full file/source metadata; the stock export cannot back up the extra metadata. Deleting or offloading a referenced file does not turn its local playlist entry into a Telegram stream. See [mixed playlists](MIXED-PLAYLISTS.md).

## beta.14 previews

Streamseek previews support selected MP3/FLAC files with an isolated player and temporary native transfer. A preview can receive the complete file; it is not a bandwidth-free stream. The current reference is a user-chosen local file, not captured YouTube audio. Excerpt matching, header inspection and user confirmation do not certify a perfect file, master or lossless origin. Real-phone audio focus, background behavior and remote-peer playback still require device qualification. See [preview behavior and limits](STREAMSEEK-PREVIEW.md).

## Downloads and player-window fixes (merged in beta.15)

Downloads uses AndroidView's default modifier for the host's optimized null argument and preserves LibraryScreen's restart scope. The player switch follows actual player-window writes rather than only the sheet constructor. Framework tests and patched-APK inspection cover these paths; validation on a physical phone is still required.

## AutoMix with community add-ons (BitChord 1.9)

Tracks selected directly from Add-ons skip AutoMix analysis in the pinned upstream
TrackAnalyzer. They can use the fixed-length crossfade fallback, but an analysed,
beat-aware transition is not guaranteed. Other source-backed tracks attempt a
matched YouTube Opus analysis; a missing match or incomplete analysis also falls
back. This update preserves that behavior. Actual transitions with signed-in
services still need Waydroid/device testing.

The Telegram phone-change/resend fix does not change AutoMix or the audio source.
A lossless or hi-res label from a community add-on is a provider claim until the
resolved audio has been inspected; it does not establish recording provenance.
