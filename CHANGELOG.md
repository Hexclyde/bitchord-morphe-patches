# Changelog

## 1.0.7

- **Fixed songs with a word like "You're" in the title playing from YouTube.** BitChord drops apostrophes before asking add-ons, so it looked for "Nobody Knows You When **Youre** Down and Out". Telegram's search matches whole words and found nothing, though the file was in the channel. When Telegram finds nothing now, the saved index is checked, and it treats "youre" and "You're" as the same word. Without the index, Telegram is searched again without such words (youre, dont, cant, aint, im and others), and the results are checked against the full title here.

## 1.0.6

- **Saved index:** the patch keeps a list of the music files in your selected chats on your phone. It's built slowly in the background, one page of 100 files every few seconds and only while nothing else is searching, so it doesn't add to Telegram's limits. It's topped up with new posts every few hours and rebuilt weekly, which drops deleted files. When Telegram's search is rate limited, busy or times out, BitChord's lookups are answered from it, so songs can still play from Telegram. The format legend and name suggestions search it instead of Telegram once it's built. It's on by default. Turn it off under **Music chats → Saved index**, which also deletes it. BitChord's log notes when a search was answered from the index.

## 1.0.5

- When Telegram limits your searches (a flood wait), a message says **Telegram API rate limited** and when searching starts again, so you know why songs are playing from other sources for a while. It appears once per pause, not once per search. [Troubleshooting](docs/TROUBLESHOOTING.md#telegram-api-rate-limited-appears) explains what to do.

## 1.0.4

- **Fixed songs playing from YouTube when you have them on Telegram.** Since 1.0.2, the format legend and name suggestions run their own Telegram searches in the background. When they were running, BitChord's lookup for the song you were about to play was turned away with "Telegram search is busy", and BitChord fell back to YouTube or its cached copy. The patch only let two searches run at once and refused the rest straight away. Now BitChord's lookups wait up to 10 seconds for a free search, background searches step aside while BitChord is looking something up, and up to three searches can run at once. This was the patch's own limit, not Telegram's. When Telegram asks for a pause (a flood wait), no search is sent until the pause is over, as before.
- BitChord's log (**Player ••• → Copy log**) now shows which files each Telegram search found, which ones were skipped as a different song, and when Telegram asked to pause searches. That makes reports like this easier to look into.

## 1.0.3

- **Fixed a wrong song playing from Telegram.** BitChord accepts a file whose title only *contains* the song's title, so "How I'd Love To Love You" was played for "L-O-V-E". Telegram files now need the same title (brackets and punctuation aside) within 10 seconds of the song's length, or a containing title within 3 seconds. Other sources are matched as before.

## 1.0.2

- **Format legend for every format:** besides FLAC 16bit and 24bit, songs now show MP3 320kbps, AAC 256kbps, OPUS 160kbps and so on. The legend is a small white label on a translucent grey chip.
- **Legends in the main search:** when you search, the patch also searches your selected Telegram chats for the same words, once per search, and matches the results by title, artist and length. Songs you have on Telegram show their format right in the search results, and the legend appears on rows already on screen as soon as the match comes in.
- The project has a website at [hexclyde.github.io/bitchord-morphe-patches](https://hexclyde.github.io/bitchord-morphe-patches/), so it can be found through Google.

## 1.0.1

- The Telegram page's options are switches now, and flipping one no longer jumps the page back to the top. Other actions on the page keep your place too.
- **Play surround as stereo** is off by default. Android's own mixing is the default again; turn it on under **Playback** if you prefer the patch's mix.
- The format legend remembers songs that have played in a lossless format, so it also shows in BitChord's main search, library and queue, not only in the Addons tab. While a song is playing from a lossy stream, it shows nothing.

## 1.0.0

First release in [bitchord-morphe-patches](https://github.com/Hexclyde/bitchord-morphe-patches), which replaces bitchord-telegram-morphe and will hold all my BitChord add-ons. The patches are now **Unofficial Telegram music** and **Install as Bitchord+**, and the playback source is called **Unofficial Telegram**, as Telegram's API terms ask of unofficial apps. Your settings, sign-in and track names carry over when you patch with the same Morphe signing key.

- **Audio quality:** FLAC, WAV, AIFF and ALAC files are reported to BitChord as lossless, and 24-bit files as hi-res. Other files are reported as high or low quality from their bitrate. BitChord uses this to choose between your file and other sources.
- **Album covers:** search results show the cover Telegram has for each file.
- **Names from file names:** files without a title tag, like `03 - Linkin Park - Numb.flac`, show up as "Numb" by Linkin Park, so BitChord can match them. Track numbers and tags like "(Official Audio)" or "[320kbps]" are removed.
- **Format legend:** a small "FLAC 16bit" or "FLAC 24bit" after song titles in search results, the mini player and the queue. Telegram files are labelled from their own header; songs from other sources from the format BitChord was given. The bit depth and sample rate also go to BitChord, so **Show stats for nerds** is exact.
- **Smooth quality switch:** when BitChord finds a better copy of the playing song, such as your Telegram FLAC after it started from YouTube, the new copy is loaded on BitChord's standby player and crossfaded in at the same point instead of restarting the player. If that can't happen (Automix mid-blend, for example), the switch happens the old way.
- **Play surround as stereo:** 5.1 and 7.1 files are mixed down to stereo inside BitChord's player, before its equaliser and effects, the same way `ffmpeg -ac 2` does it. The file isn't changed, nothing is converted ahead of time, and it works for every song BitChord plays.
- Each of these can be turned off under **Unofficial Telegram → Track details** or **Playback**.

## Before 1.0.0

These versions were released as bitchord-telegram-morphe.

### 0.2.3

- Saving a corrected name, or accepting a suggestion, also removes BitChord's saved copy of that song. A song BitChord has already saved from YouTube plays from that copy without checking other sources, so a renamed Telegram file wasn't used until the copy was gone.
- The Track names card updates while it's open: new suggestions appear without reopening the page.

### 0.2.2

- **Track names:** fix a Telegram file's title or artist on your phone without uploading it again, under **Unofficial Telegram → Track names**. BitChord then finds and matches the file under the corrected name.
- **Suggested fixes:** when a song plays from another source and one of your chats has a file by the same artist, about as long, whose title is a few letters off, the patch suggests the correct name. Nothing changes until you accept.
- Both can be turned off on the same page.

### 0.2.1

- Removed the extra "Unofficial Telegram setup" launcher icon. Telegram setup is under **Settings → Account & integrations → Unofficial Telegram**.
- Tapping **Telegram (on device)** under **Sources** now opens the Telegram settings page instead of an editor showing an internal address.
- The package name is unchanged, so 0.2.1 installs over 0.2.0 when you patch with the same Morphe signing key.

### 0.2.0

Initial experimental release for BitChord 1.8.1.

- Sign in to Telegram with your own API credentials and account.
- Search selected chats and stream audio with seeking.
- Browse channel posts and official sponsored messages in Channel View.
- Adjust the Telegram audio cache and view the playing source and bitrate.
- Choose an installation name; the default is **Bitchord+**.

See the [installation guide](docs/MOBILE.md) and [known limitations](docs/KNOWN_LIMITATIONS.md).
