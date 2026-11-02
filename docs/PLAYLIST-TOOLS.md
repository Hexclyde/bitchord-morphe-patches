# Playlist tools

Enable **Mixed-source playlists** in Patches & tweaks, then open **Playlist tools** from Library or the patch settings.

- **Open playlist link** accepts public or unlisted YouTube playlist links. Choose Open, Keep in library, or Save a device copy. A saved link follows the source playlist; a device copy is independent and editable.
- **Save queue** saves the current track and the remaining queue in playback order, excluding played history and duplicate track identities.
- Device playlists support renaming, moving tracks, removing tracks and deletion. These actions change the device copy only.

These tools store metadata, not downloaded audio. YouTube playback still needs a working connection and accessible content. Private playlists require the appropriate account. Radio mixes cannot be bookmarked. Copies are limited to 5,000 unique tracks; a failed continuation prevents saving a partial copy.

## Native import

Choose **Import playlist file** to read JSON, CSV, TSV, TXT or M3U metadata directly on the device. CSV supports title, artist, album, duration and ISRC columns; TXT accepts one `Artist - Title` per line. M3U reads EXTINF titles and durations. Compatible version-1 playlist-backup JSON can come from any music provider.

YouTube track identities are preserved directly. Other catalog identities are matched through anonymous YouTube Music search. The recording matcher checks title, artist, version and duration before proposing a match. Review candidates or skip unresolved tracks, then confirm creating independent device playlists. No external service is modified by importing a file.

Files are limited to 16 MiB, 200 playlists and 5,000 entries. Metadata import does not download audio. Playback still needs a working service connection. Unavailable and skipped tracks are reported before saving.

## External playlist sync

Open **Playlist sync** and connect Spotify with a personal developer application's client ID and YouTube Music through BitChord's account connection. Spotify uses PKCE on the phone; it needs no client secret or dedicated server. The application's redirect URI must be `app.bitchord.sync://spotify/callback`. Spotify developer account and app restrictions still apply.

Pair two existing editable playlists. Review recording matches and exclusions, then preview and confirm the changes. Sync merges membership in both directions. Removals start disabled, require confirmation across complete reads, and have a separate operation limit. Account changes, incomplete reads, unavailable entries and duplicate recordings stop the operation for review. A pending verification record prevents an uncertain write from being blindly repeated.

This version runs sync manually. It does not schedule background sync, synchronize playlist names or ordering, or connect other external services. File import remains available for metadata from other services. No external playlist is modified by opening a link, saving a device copy or importing a file.

OAuth credentials are encrypted with Android Keystore in app-private storage. Playlist links, matching decisions, exclusions and verification records are stored locally. Disconnecting removes the connection credentials; removing a pair leaves both remote playlists intact. New playlist tools and sync phrases currently fall back to English where a translation has not been supplied.
