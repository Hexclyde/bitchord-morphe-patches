# BitChord Morphe Patches (Bitchord+)

[Morphe](https://morphe.software) patches for the [BitChord](https://github.com/kushagrasinghx/BitChord) Android music player. The main one, **Unofficial Telegram music**, lets BitChord search and play music from your own Telegram channels, in its original quality, entirely on your phone. No server, bot or hosting is involved.

**[Add this source to Morphe](https://morphe.software/add-source?github=Hexclyde/bitchord-morphe-patches&name=BitChord%20Morphe%20Patches&app=com.music.bitchord)** · [Releases](https://github.com/Hexclyde/bitchord-morphe-patches/releases) · [Setup guide](docs/MOBILE.md)

> **Status:** experimental (1.1.0-beta.2, a testing build). It only works with BitChord **1.8.1**. Check the [known limitations](docs/KNOWN_LIMITATIONS.md) before installing.
>
> I've only been able to test it on an **arm64-v8a** phone. The armeabi-v7a and x86_64 builds pass the same automated checks, but I haven't run them on a real device. If you're on one of those, please give it a try and [open an issue](https://github.com/Hexclyde/bitchord-morphe-patches/issues/new/choose) if you run into problems. Hearing that it works is useful too.

## Patches

| Patch | What it does |
| --- | --- |
| **Unofficial Telegram music** | Search and play audio from your own Telegram channels and chats through TDLib, using your own account. Android 8 or newer. |
| **Install as Bitchord+** | Installs next to your regular BitChord as **Bitchord+**, or under a name you choose. |
| **Sign in with MicroG** | Signs in to YouTube Music with an account that’s already in [MicroG RE](https://github.com/MorpheApp/MicroG-RE), instead of Google’s sign-in page. Only shows when MicroG RE is installed. |
| **Sing** | Turns the lead vocal down so you can sing over the song. Ported from [Auris](https://github.com/OreoMuncher45/Auris). Off by default. |
| **Pitch control** | Moves the key up or down without changing the speed. Off by default. |
| **Loudness level** | Sets how loud loudness normalisation plays songs. Off by default. |
| **Sleep timer fade-out** | Fades the song out before the sleep timer pauses it. Off by default. |
| **Battery saver** | Lets the phone's audio chip decode songs. Off by default. |
| **Lossless addons over Bluetooth** | Keeps lossless addons in use over Bluetooth. Off by default. |

More BitChord add-ons will be added to this repository over time.

<p>
  <img src="docs/screenshots/application-setup.png" width="200" alt="API credentials screen">
  <img src="docs/screenshots/connected-library.png" width="200" alt="Connected library">
  <img src="docs/screenshots/advanced-settings.png" width="200" alt="Advanced settings">
  <img src="docs/screenshots/track-details.png" width="200" alt="Track details settings">
  <img src="docs/screenshots/track-names.png" width="200" alt="Track names">
</p>

## Unofficial Telegram music features

- Search your selected Telegram channels by title, artist or file name, from BitChord's **Addons** tab, picking them from any folder or your archive
- Stream and seek through the original files, with no transcoding
- FLAC, WAV, AIFF and ALAC are reported to BitChord as lossless, so it can prefer them over lossy copies
- Fake lossless files are caught: a FLAC made from an MP3 shows as **FLAC 16bit · from ≈128 kbps** and is ranked as that MP3, and upsampled or padded hi-res files show what they really are
- Album covers from Telegram in search results
- A saved index of your chats' music answers when Telegram's search is rate limited or busy
- Save a channel as a BitChord playlist, or download all of it
- Albums shared as zip files: every song inside is found, played and matched like any other file, without downloading the zip
- Albums kept as one FLAC or WAV file with a cue sheet: each track becomes its own song, played exactly from its start to its end
- A chat's albums, each to save as a playlist or download
- Back up and restore your settings, back up as you sign out and restore in one tap when you're back, and share a chat's index so friends in the same channel can skip indexing it
- Check by sound whether a Telegram file with a different length is the same recording as the song
- Copy music from community channels into a private channel you own, at a pace you set, with lossless-only and duplicate filters
- Untagged files get their title and artist from the file name (`03 - Artist - Title.flac`), or, with your free AcoustID key, by sound
- 5.1 and 7.1 files play as proper stereo, mixed inside the player without converting the file
- A small format legend next to song titles (FLAC 24bit, Dolby Atmos, DD+ 5.1, MP3 320kbps, …), in the main search too
- Dolby Digital, Dolby Digital Plus and Dolby Atmos files, with Atmos passed on to BitChord as Atmos
- When a better copy turns up mid-song, it fades in without a gap
- Fix misnamed files on your phone, with suggestions, without uploading them again
- Browse channel posts in Channel View
- Control the size of the Telegram audio cache
- See the playing source and bitrate in **Show stats for nerds**
- The Telegram page in 14 languages, following your phone: Spanish, Portuguese, French, German, Russian, Turkish, Arabic, Urdu, Hindi, Bengali, Chinese, Japanese, Korean and Indonesian

Every extra (quality, fake lossless checks, covers, file names, naming by sound, format legend, smooth switching, surround mixing, sound checks, the saved index, the main channel copy, name fixes and suggestions) can be turned off in the app.

## Playback extras

Six separate patches, each off by default. Turn on the ones you want in Morphe. Any of them adds a **Playback extras** page at the bottom of BitChord's **Settings → Equalizer** page, showing the settings of the ones you added:

- **Sing:** turns the lead vocal down so you can sing over the song, from a little to all of it. By default only the voice range is cut, only while someone is singing, and drum hits are left alone; **Whole centre** removes everything in the middle instead, which suits older recordings. Ported from [Auris](https://github.com/OreoMuncher45/Auris) by OreoMuncher45.
- **Pitch:** up or down by up to 12 semitones without changing the speed. BitChord's speed setting keeps the pitch you set.
- **Loudness level:** how loud songs play with BitChord's loudness normalisation on, from -23 to -8 LUFS. BitChord's own level is -14 LUFS. It applies to the songs BitChord normalises, the ones YouTube gives a loudness for.
- **Sleep timer fade-out:** when the timer's time is up, the song fades out over 10 seconds before it pauses. You can turn the fade off on the page.
- **Battery saver:** lets the phone's audio chip decode songs it can, so the processor can sleep during long listens. While a song plays this way, the equaliser, loudness normalisation, Sing, spatial audio and crossfade don't work on it. It stays off on the page until you turn it on.
- **Lossless addons over Bluetooth:** BitChord skips addons marked lossless while the phone can't output lossless audio, such as over Bluetooth. With this on they're used anyway and the phone sends them with its best Bluetooth codec. It stays off on the page until you turn it on.

Everything is at BitChord's own behaviour until you change it, and none of them needs the Telegram patch.

## Requirements

- Android 8.0 or newer
- The original [BitChord 1.8.1 APK](https://github.com/kushagrasinghx/BitChord/releases/tag/v1.8.1) (arm64-v8a, armeabi-v7a or x86_64)
- [Morphe Manager](https://github.com/MorpheApp/morphe-manager/releases)
- A Telegram account and an API ID and hash from [my.telegram.org](https://my.telegram.org/apps)

## Quick start

1. [Add this source to Morphe](https://morphe.software/add-source?github=Hexclyde/bitchord-morphe-patches&name=BitChord%20Morphe%20Patches&app=com.music.bitchord). On the source card, enable **Experimental app versions**.
2. Patch BitChord 1.8.1 with **Unofficial Telegram music**, **Install as Bitchord+** and, if you like, **Sign in with MicroG** and any of the [playback patches](#playback-extras). Leave the API ID at `0` and the API hash empty.
3. Install the patched app and open **Settings → Account & integrations → Unofficial Telegram**.
4. Enter your API ID and hash, sign in, and pick your music chats.
5. Go to **Search → Addons** to find and play your audio.

Testing builds are separate from this source; see [Testing builds](#testing-builds).

If the source link doesn't work, download the `.mpp` from the [releases page](https://github.com/Hexclyde/bitchord-morphe-patches/releases) and add it as a Local source.

Step-by-step guides:

- [Installing with Morphe](docs/MOBILE.md)
- [Connecting Telegram](docs/SETUP.md)
- [FAQ](docs/FAQ.md)
- [Troubleshooting](docs/TROUBLESHOOTING.md)

## Testing builds

New versions go to a `testing` branch first, as GitHub pre-releases with versions like `1.1.0-beta.1`. The normal source above only gets a version once it's out of testing, whatever its **Pre-release patches** switch is set to.

To try a testing build, add a second source in Morphe with this URL and turn on **Experimental app versions** on it:

```text
https://raw.githubusercontent.com/Hexclyde/bitchord-morphe-patches/refs/heads/testing/patches-bundle.json
```

Use this exact link. Morphe changes other GitHub branch links to `main`, so they would give you the normal version.

Patch from the testing source, and turn the normal source off for that run so the patches aren't listed twice. When the version is released, you'll get it from the normal source as an update. To go back to the current release, remove the testing source and patch again from the normal one. You can also download a testing build's `.mpp` from its pre-release on the [releases page](https://github.com/Hexclyde/bitchord-morphe-patches/releases) and add it as a Local source.

## Privacy

The patch creates a normal Telegram client session on your phone. You can revoke it at any time in the official Telegram app under **Settings → Devices**. Your API credentials are encrypted with Android Keystore. More details are in [PRIVACY.md](PRIVACY.md).

## Disclaimer

**Use these patches responsibly.** They're free to use and provided as-is, without any warranty. I'm not responsible or liable in any way for what happens to your Telegram account or channels while you use them, including your account being limited or banned, or a channel being taken down.

By downloading, installing or using these patches, you accept these terms. If you don't agree with them, don't use them. The [GPL license](LICENSE) they're released under also says they come with no warranty.

This project doesn't provide any music. You're responsible for what you upload and play, so only use recordings you own or have permission to use. The patches don't unlock premium features or get around paid or protected content.

This is an independent project and isn't affiliated with BitChord, Telegram or Morphe.

## Contributing

Bug reports and pull requests are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) first. Translation fixes go in `translations/<language>.json`; `python3 tools/translations.py` rebuilds the table the add-on reads. If you want to build from source, see [docs/BUILDING.md](docs/BUILDING.md).

## Credits

- [BitChord](https://github.com/kushagrasinghx/BitChord) by kushagra (kushagrasinghx) and its contributors, the app these patches are for.
- [Auris](https://github.com/OreoMuncher45/Auris) by OreoMuncher45, a fork of BitChord. Sing is ported from its vocal remover.
- [Prism](https://github.com/Yigder/Prism) by Yigder and [Stash](https://github.com/rawnaldclark/Stash) by Rawnald Clark, with Evo (ParaliyzedEvo). Their pitch, loudness and battery features shaped the playback patches extras; no code was taken from them.
- [MicroG RE](https://github.com/MorpheApp/MicroG-RE) and [Morphe](https://morphe.software) by the Morphe team.

## License

[GPL-3.0-or-later](LICENSE). Bundled third-party components keep their own licenses. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
