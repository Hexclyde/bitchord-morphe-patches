# Known limitations

These apply to the current release on BitChord 1.8.1. This version hasn't been widely tested on real phones yet, so behavior may differ between devices.

I've only tested it on an arm64-v8a device. The armeabi-v7a and x86_64 builds pass the same automated checks but haven't been tried on real hardware. If you run one of them, please [report what you find](../CONTRIBUTING.md).

## Search and playback

- Regular BitChord search may still play from YouTube. Use **Search → Addons** to get Telegram results.
- A search returns up to 200 tracks across your selected chats.
- Only one Telegram account is supported. You can't create a new account from the app, and some login methods aren't supported.
- Only the standard 1.8.1 APKs are supported. TV, Wear OS, split APKs and other BitChord versions are not.

- Copying into your main channel starts from a community channel only once the saved index has gone through all of it, since copies go oldest post first. A channel with 200,000 files takes a few hours.
- Dolby Atmos in Dolby TrueHD shows as TrueHD; it plays as 5.1 or 7.1. Dolby AC-4 files only play on phones with an AC-4 decoder. Dolby Atmos plays with its height sound only on phones and speakers that support it.
- The Telegram page is translated into 14 languages. Error messages that Telegram itself sends stay in English.

## Channel View

- Only selected, accessible channels without content protection can be opened.
- Telegram links and channel videos open in the Telegram app, not inside BitChord.
- Some formatting, custom emoji and link previews in sponsored messages aren't rendered yet.

## Saved files

- Protected, secret and self-destructing media can't be played.
- Audio already in BitChord's cache or downloads stays playable after you sign out or remove a chat. Clear those copies separately.
