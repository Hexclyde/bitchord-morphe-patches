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

No indexing step is needed. Search runs against your selected chats when you enter a query. A query returns up to 200 tracks, so search by a specific title, artist or filename.

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

## What does tapping Telegram under Sources do?

It opens the Unofficial Telegram settings page. Since 0.2.1 it no longer opens BitChord's add-on editor, which only showed an internal `https://telegram.bitchord.invalid/...` address. That address never leaves your phone: the patch handles it inside the app and there's no website or server behind it.

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
