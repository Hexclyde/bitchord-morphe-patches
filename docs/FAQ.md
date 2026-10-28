# Frequently asked questions

## Do I need a server?

No. Your phone connects directly to Telegram. The audio stays in your Telegram chats and downloads to the phone as it plays. You need internet access and space for cached files.

## Why do I need API credentials and a login?

The API ID and hash identify your Telegram application. Your account login gives that application access to your account. Both are needed. A bot token cannot replace them. Follow the [setup guide](SETUP.md).

## Can I use a private channel?

Yes, if your signed-in account can access it. Join it in the official Telegram app first. Every listener needs their own permitted access. Protected or self-destructing content is excluded from this player.

## Can I delete the original file from my phone?

Yes, after its upload to Telegram has completed. The channel message and file must remain available, and your account must still have access. Playing it will download and cache bytes on your phone again.

## Does it scan my whole channel first?

You don't have to wait for anything. Search runs against your selected chats when you enter a query, and returns up to 200 tracks, so search by a specific title, artist or filename.

With **Saved index** on (under **Music chats**), the patch also lists your selected chats' music in the background, slowly, so it never looks like a burst of searches. Once it's built, lookups are answered from it, and Telegram is only searched when it has nothing. It also answers when Telegram's search is rate limited or busy. Turning it off deletes it.

The **Saved index** line counts the files as they come in, against Telegram's own count for the chat. It goes through about 3,000 files a minute, so a channel of 200,000 files takes over an hour the first time, and uses about 150 MB of storage.

## Why does regular search still play from YouTube?

Regular BitChord search can choose another provider. Open **Search → Addons** and select a Telegram result. Automatic replacement of regular search results with Telegram files is not reliable yet.

## Does it change the audio quality?

The patch reads the uploaded file without transcoding it. It does not turn a lossy recording into lossless audio or add bitrate controls. Increasing the cache only changes storage use.

With **Report audio quality** on, the patch tells BitChord what each file is: FLAC, WAV, AIFF and ALAC count as lossless, and other files as high or low quality from their bitrate. BitChord uses that to choose between your file and its other sources, so a 128 kbps MP3 can lose to a better copy elsewhere. Turn it off to let BitChord judge from the file type alone.

## What does the bitrate display mean?

Enable **Settings → Advanced options → Show stats for nerds**. The overlay shows the playing source and available bitrate. `≈… avg` is an approximate file average; `≈… estimated` is an estimate from the selected quality. `PCM` describes decoded audio data. Missing information shows **Bitrate unavailable**. These values do not measure download speed or Bluetooth bitrate.

## Why is the app using more storage than the Telegram cache setting?

The Telegram setting is a target, and active files can exceed it. BitChord's own song cache and downloads use additional space. Clear each separately. Signing out does not delete existing BitChord copies.

## A file has the wrong name. Do I need to upload it again?

No. Open **Settings → Account & integrations → Unofficial Telegram → Track names**, search for the file under **Fix a track name**, and correct its title or artist. The correction stays on your phone; the file on Telegram isn't changed. BitChord then finds and matches the file under the new name, so a regular search can play it instead of YouTube. BitChord may keep an earlier search result for a few minutes.

With **Suggest fixes** on, the page also lists files that look misnamed. A suggestion appears when a song plays from another source and one of your chats has a file by the same artist, about as long, whose title is a few letters off ("Ine More Light" for "One More Light"). Nothing is renamed until you tap **Use name**.

Saving a name also removes BitChord's saved copy of that song, if it played from YouTube recently. BitChord plays a song it has already saved without checking your other sources, so this is what lets the next play pick your Telegram file. Songs you started from an album or playlist page may not carry a length, and BitChord then won't switch sources mid-song; start the song from search if it still plays from YouTube.

You can turn off **Use corrected names** to see every file as uploaded, without losing your corrections, and **Suggest fixes** to stop the background searches.

## My files have no title tags. Will they still match?

Usually. With **Names from file names** on (under **Track details**), a file without a title tag gets its name from the file name: `03 - Linkin Park - Numb (Official Audio).flac` becomes "Numb" by Linkin Park. Track numbers and tags like "(Official Audio)", "[HQ]" or "[320kbps]" are removed; "(Live)" and "(Remix)" are kept because they're a different recording. If a name still comes out wrong, fix it under **Track names**.

## Can it play 5.1 or 7.1 files as stereo?

Yes. Turn on **Play surround as stereo** (under **Playback**; it's off by default). With it on, surround files are mixed down to stereo inside BitChord's player: the centre and surround channels are mixed in 3 dB lower and the bass (LFE) channel is left out, the same mix as `ffmpeg -ac 2`. The file isn't converted or changed, so nothing extra is stored and there's no wait. It applies to every song BitChord plays and takes effect from the next song. With it off, Android does its own mix.

## What is the "FLAC 24bit" next to a title?

The format legend, under **Track details**. It shows the format of the copy you'd hear: FLAC 16bit or 24bit, ALAC, WAV, or a lossy format with its bitrate, like MP3 320kbps or OPUS 160kbps.

- **Addons tab:** Telegram files are labelled from the file itself. The patch reads the start of a few lossless files in each search (and of any file you play) to find the bit depth, and remembers it.
- **Main search:** when you search, the patch searches your selected Telegram chats for the same words, once per search, and matches results by title, artist and length. Songs you have on Telegram get their legend as soon as that match comes in.
- **Everywhere else:** songs show the format of the stream BitChord used the last time they played.

Turning the legend off also stops the extra search.

## A better copy started playing halfway through a song. Will I hear a gap?

Not with **Smooth quality switch** on (under **Playback**). BitChord sometimes starts a song from YouTube and finds a better copy, such as your Telegram FLAC, a few seconds later. The patch has BitChord load that copy on its standby player while the current one keeps playing, then crossfades between them at the same point. If the standby player isn't free, for example during an Automix transition, the switch happens BitChord's usual way, with a short pause. The crossfade mixes decoded audio, so that half second isn't bit-perfect.

## Why don't I see album covers?

Telegram only has a cover for files that were uploaded with one, or that it recognises. Covers are downloaded the first time a file shows up in a search, and BitChord keeps search results for a few minutes, so a new cover can take a later search to appear. Turn **Album covers** off under **Track details** to stop downloading them.

## What does tapping Unofficial Telegram under Sources do?

It opens the patch's own settings page, the same one as **Settings → Account & integrations → Unofficial Telegram**. BitChord's add-on editor isn't used for it, because there's nothing to edit there.

## What is telegram.bitchord.invalid?

It's the address BitChord uses to talk to the patch, and you'll see it in BitChord's log as `https://telegram.bitchord.invalid/***`. It never leaves your phone: the patch answers it inside the app, and there's no website or server behind it. Domains ending in `.invalid` are reserved and can't exist on the internet, so even if a request did get out, it couldn't reach anyone.

## What does "Check matches by sound" do?

BitChord only plays your Telegram file instead of a song when the file's length is within a few seconds of the song's. A file with the right title but a length further off is usually another recording, but sometimes it's the same one with a longer fade, a hidden track or extra silence.

With **Check matches by sound** on (under **Playback**, off by default), the patch notes those files. The next time the song plays from its start, it listens to its first 15 seconds as BitChord plays them, reads the first 45 seconds of the Telegram file, and compares the two with audio fingerprints, the method Shazam uses. If they're the same recording, starting at the same point, a message says so and the file is used the next time the song plays.

It compares recordings, not quality: a match means the same recording, not that the file is better. Each check downloads up to about 15 MB of the Telegram file. The song's audio is only used on your phone for the comparison and isn't kept or sent anywhere.

## Can I play or download a whole channel?

Yes. Under **Music library → Playlists and downloads**, pick a chat and choose:

- **Save as playlist** makes a playlist named after the chat in BitChord's **Library**, oldest post first. Save it again later to bring it up to date; the old copy is replaced.
- **Download all** puts the files in BitChord's downloads. They're saved at BitChord's download quality, so set **Download quality** to **Lossless** to keep FLAC files as FLAC.

Both take the chat's newest 1,000 music files. The menu shows how many songs that is, and the download's size, before you confirm.

Both use the saved index, so **Saved index** has to be on and done with that chat. Making the playlist sends nothing to Telegram. Each download fetches its file from Telegram, the same as playing it.

## Can it play albums shared as zip files?

Yes, with **Saved index** on. When the index comes across a zip, it reads only the end of the zip, where the list of files is, and the first few KB of each song. Every audio file inside (FLAC, WAV, AIFF, ALAC, MP3 and the rest) becomes a song of its own: named from its file name, with its artist from the file name or the album's folder or zip name, and its length and format from its header. You can search for it by title, artist or album, play it, add it to playlists and download it, and BitChord can match it like any other Telegram file.

Playing a song reads just that song's part of the zip. Most music zips are stored without compression, so these play and seek like any other file. A song that was compressed inside the zip plays too, but seeking far into it takes a moment, because it's decompressed from its start. Zips split into parts (`.z01`, `.zip.001`) and password-protected zips aren't supported. The **Saved index** line shows how many zips are still waiting to be opened.

With **Copy into main channel** on, a zip is copied to your main channel once, like any other post.

## Can I collect music from other channels into my own?

Yes, under **Main channel**. Choose a private channel you created as your main channel, add the community channels to copy from, and turn on **Copy into main channel**. Every option is off until you turn it on, and each one explains itself first.

- Files are copied, not forwarded, so they show as your own posts without "Forwarded from". Telegram doesn't upload them again, and a copy stays in your channel even if the community channel deletes the original.
- **Keep in sync** also copies new posts as the saved index finds them. Without it, the copy stops at what the channels had when it started.
- **Lossless only** copies FLAC, ALAC, WAV and AIFF files and skips the rest.
- **Skip duplicates** doesn't copy a file your main channel already has: the same Telegram file, or the same title, artist, length and format. Nothing is ever deleted.
- The pace is 1 to 500 files an hour, and copying pauses whenever Telegram asks it to.

The files come from the saved index, so **Saved index** has to be on; the community channels and your main channel are indexed too while copying is on. Only private channels you created can be the main channel, and channels with protected content can't be copied from.

**This carries a risk to your account.** Telegram watches for accounts that copy many messages, and can limit or ban one that does it too fast. A slower pace is safer. You're also responsible for what you copy: only copy music you're allowed to keep.

## Can I rename the installed app?

Yes. In Morphe, open **Install as Bitchord+ → Installation name** before patching. Leave it blank for **Bitchord+**. Changing the name does not create another separate installation or change your registered Telegram API app title.

## Why are screenshots blocked on the Telegram page?

That page handles API credentials and login details, so it requests Android's screenshot and recording protection. You can describe a problem in text when asking for help. Never share your hash, login codes or passwords.

## Why do sponsored messages appear in Channel View?

They are official messages returned by Telegram. Their menu can show sponsor details and reporting choices. Telegram may return none, and there is no local switch to suppress them. They are shown in Channel View, not in the audio player. Some Telegram links open in an installed Telegram client.

## Can I use more than one Telegram account?

Only one signed-in account is supported. Sign out before switching accounts. To revoke a session, use the official Telegram app's **Settings → Devices** page.

## Does this unlock premium features or include music?

No. It adds a Telegram audio source and nothing else. You are responsible for the files you upload, share and play.

## Is this an official Telegram or BitChord release?

No. It is an independent, experimental project. See [Known limitations](KNOWN_LIMITATIONS.md).
