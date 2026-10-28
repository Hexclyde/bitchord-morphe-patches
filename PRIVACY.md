# Privacy and account access

The Telegram patch runs inside Bitchord+. Your phone connects to Telegram using your own account. It searches the chats you select and downloads audio as needed. No project-operated server, proxy or bot is involved.

This page covers the Telegram patch only. BitChord, Morphe and Telegram have their own privacy policies.

## What stays on your phone

| Information | Storage |
| --- | --- |
| Telegram API ID and hash | Encrypted with Android Keystore in the app's private storage |
| Telegram account session | Stored privately by TDLib; its database key is protected with Android Keystore |
| Selected chats | Saved in the app's private settings |
| Corrected track names and suggestions | Saved in the app's private storage; cleared when you sign out |
| The last 200 songs that played from other sources (title, artist, length) | Saved in the app's private storage while a Track names option is on, to find BitChord's saved copy of a song you rename; cleared when you sign out |
| Format legends of songs that played in a lossless format | Saved in the app's private storage; removed when you sign out |
| File format details (bit depth, sample rate, channels) | Saved in the app's private storage for the format legend; removed when you sign out |
| The saved index: title, artist, length, file name and format of the music files in your selected chats | Saved in the app's private storage while **Saved index** is on; deleted when you turn it off or sign out |
| Sound check results: which Telegram files are the same recording as which songs | Saved in the app's private storage while **Check matches by sound** is on; removed when you sign out. The song's audio that's compared is held in memory for the comparison only |
| Main channel settings and copy progress: which channels, which files have been copied | Saved in the app's private storage; removed when you sign out |
| Album covers | Copied into the app's private storage while **Album covers** is on; removed when you turn it off or sign out |
| Search results | Temporarily cached; BitChord also has its own search cache |
| Played audio | Telegram's media cache and, where used, BitChord's song cache or downloads |

Login codes and passwords are sent to Telegram to complete the requested login step. The extension does not intentionally save them. If you enter API details into Morphe's patch options instead, those values become extractable from the APK. Leave those options at **0** and **empty**, then enter your details inside the installed app.

## What Telegram can see

Telegram receives account login, selected-chat searches and media requests. With **Suggest fixes** on, a song that plays from another source also triggers a search of your selected chats for that song's artist. Turn it off under **Track names** to stop those searches. With **Album covers** on, the patch downloads the cover Telegram has for each file in your search results. With **Format legend** or **Report audio quality** on, it also downloads the start of a few lossless files in each search to read their format. With **Format legend** on, each search you make in BitChord's main search is also sent to Telegram as a search of your selected chats, the same as searching in the Addons tab. With **Copy into main channel** on, the patch asks Telegram to copy the chosen files from your community channels into your main channel, at the pace you set. With **Check matches by sound** on, the patch reads the first 45 seconds of a Telegram file it's checking, as playing it would. Saving a channel as a playlist uses the saved index and sends nothing to Telegram; downloading a channel downloads each of its files from Telegram, as playing them would. With **Saved index** on, the patch also lists the music files in your selected chats in the background, one page of 100 files every few seconds while nothing else is searching, and checks for new posts every few hours. Opening Channel View requests posts and official sponsored messages. Reading visible posts and playing identifiable Telegram tracks can mark those messages as read. Foreground use can show the account as online.

Sponsored-message views are reported when their text is fully visible in the active Channel View. Opening destinations or using reporting options sends the corresponding action to Telegram. Opening an external website also contacts that website after you confirm. The patch contains no analytics or tracking.

## Account permissions

**This is a normal Telegram client session, not a music-only authorization.** Choosing chats limits what the interface searches; it does not reduce the underlying session's account permissions. The patch and BitChord run in the same app and must both be trusted.

Your regular BitChord installation has separate private data. The Telegram setup page requests screenshot and recording protection, but this does not protect against a compromised phone or an external camera.

## Clear files or revoke access

- Stop playback and use **Clear Telegram audio cache** on the Telegram page to clear that cache.
- Manage BitChord's song cache and downloads separately.
- Use **Session → Sign out** to sign out of the integration.
- To revoke the session, open the official Telegram app and go to **Settings → Devices**.

Signing out, deselecting a chat or revoking access does not erase audio already saved by BitChord. Uninstalling removes the app's private data; files exported to shared storage can remain.

## Asking for help

Do not post API hashes, login codes, passwords, session files, signing keys or private channel links. Check logs for account details, file paths and private titles before sharing them.

If a session is exposed, revoke it in Telegram and remove the public material. For a security issue, follow [Security](SECURITY.md).

Telegram's [privacy policy](https://telegram.org/privacy) and [API terms](https://core.telegram.org/api/terms) apply to your use of its services.
