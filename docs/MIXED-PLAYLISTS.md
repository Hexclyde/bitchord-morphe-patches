# Mixed-source playlists

Select **Mixed-source playlists** in Morphe and enable its toggle in **Settings → Patches & tweaks**. Use **Add to playlist** from a track's long-press/overflow menu, or **Library → New playlist**. The new **On this device · any audio source** destination accepts downloaded files, local music, YouTube tracks and source-specific addon tracks in one playlist. Completed download rows also offer **Add to playlist** when this patch is enabled.

These playlists appear in BitChord's existing **On device** Library shelf and open in its existing playlist/detail/player screens. No Google sign-in is required for a device playlist. Local-only and YouTube Music playlist destinations remain available through their original actions. A phone file cannot be uploaded into a YouTube Music playlist through this patch.

The patch preserves source IDs and all persistent Song fields, including local URIs/paths, download format, artist credits and quality metadata. Temporary queue/radio entries are cleared. Existing Spotify-import device playlists can be extended, without losing their tracks. Membership selection avoids duplicate entries; unchecking removes membership. Native rename and delete actions update the saved patch data.

Disabling the feature restores the original menu behavior while retaining existing playlists. File playback still requires access to the original file; a removed file is not automatically copied. Stream playback requires the named source to remain enabled and available. Public Soulseek is download-only, so add its completed file to a playlist. A Telegram offload can remove a file referenced by a device playlist; restore that original before using that local entry.

The extra metadata is kept in the patch's device preferences. The stock JSON export and an APK with the patch removed do not preserve this added metadata. Keep the patch installed when updating and retain phone backups. Tests cover file/source identities across restore, existing playlist promotion, membership, rename/delete and disabling; real-device playback qualification remains pending.
