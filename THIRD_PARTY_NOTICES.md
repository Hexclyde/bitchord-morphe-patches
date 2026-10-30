# Third-party notices

The patch code is licensed under GPL-3.0-or-later. Third-party components keep their own licenses, and copies of those licenses are in [licenses/](licenses).

## Bundled in the release

| Component | Used for | License |
| --- | --- | --- |
| [Google Material Design Icons](https://github.com/google/material-design-icons) | Rounded Settings icon matching BitChord's `Icons.Rounded.Settings`, rendered from the same 24px path | [Apache License 2.0](licenses/APACHE-2.0.txt) |
| [TDLib](https://github.com/tdlib/td) and its Java bindings | Telegram login, search and downloads | [Boost Software License 1.0](licenses/TDLIB-BOOST.txt) |
| [TGX-Android/tdlib](https://github.com/TGX-Android/tdlib) | Prebuilt native TDLib libraries | See TDLib, OpenSSL and libc++ |
| [OpenSSL](https://github.com/openssl/openssl) | TLS for TDLib | [Apache License 2.0](licenses/OPENSSL-APACHE.txt) |
| LLVM libc++ ([Android NDK](https://developer.android.com/ndk)) | C++ runtime | [libc++](licenses/LIBCXX-LICENSE.txt), [LLVM notice](licenses/LLVM-NOTICE.txt), [NDK notice](licenses/ANDROID-NDK-NOTICE.txt) |

The native libraries are shipped unchanged. They were built from these source commits:

- TGX-Android/tdlib: [`7ec241d`](https://github.com/TGX-Android/tdlib/tree/7ec241d026ee3563e615c25a700117c2abbaea19)
- tdlib/td: [`ea3f69c`](https://github.com/tdlib/td/tree/ea3f69ce11aef458bfbb09869b809ede4447486f)
- OpenSSL: [`45e844f`](https://github.com/openssl/openssl/tree/45e844fa2a14ec92d146bd8f5778ac130b6625fb)

## Code ported from other projects

| Project | What | License |
| --- | --- | --- |
| [Auris](https://github.com/OreoMuncher45/Auris) by OreoMuncher45 and the BitChord contributors, a fork of [BitChord](https://github.com/kushagrasinghx/BitChord) by kushagra | Sing (`VocalCut.java`), from `VocalCutDsp.kt` and `VocalCutOriginal.kt` at [`4c07477`](https://github.com/OreoMuncher45/Auris/tree/4c07477533d8ce9fb0f2ba7f5cc33e070e4d8f1f), translated to Java | [GPL-3.0](licenses/GPL-3.0.txt) |

The **Pitch control**, **Loudness level**, **Sleep timer fade-out**, **Headphone correction**, **Equaliser per output** and **DJ mode** patches follow features of [Prism](https://github.com/Yigder/Prism) by Yigder (GPL-3.0-or-later), **Per-download quality** follows [Stash](https://github.com/rawnaldclark/Stash) by Rawnald Clark (GPL-3.0), and **Lossless addons over Bluetooth** follows Auris's lossless-first approach. Their code is written for BitChord; nothing was copied from Prism or Stash.

## Fetched or used at run time, not bundled

- **Headphone correction** downloads headphone profiles from [AutoEq](https://github.com/jaakkopasanen/AutoEq) by Jaakko Pasanen ([MIT License](https://github.com/jaakkopasanen/AutoEq/blob/master/LICENSE)) when you pick a model, straight from its GitHub repository, and keeps them on the phone. The filter formulas are the peaking and shelving biquads of Robert Bristow-Johnson's Audio EQ Cookbook.
- The **JamesDSP** patch works with [RootlessJamesDSP](https://github.com/timschneeb/RootlessJamesDSP) by Tim Schneeberger and [JamesDSPManager](https://github.com/james34602/JamesDSPManager) by James Fung (both GPL-3.0), installed separately by you. Nothing of theirs is bundled; BitChord's audio session is announced to them through Android's standard audio-effect broadcasts.

## Not bundled

[BitChord](https://github.com/kushagrasinghx/BitChord) is a separate GPL-3.0 app (patched against [`7d956f2`](https://github.com/kushagrasinghx/BitChord/tree/7d956f211b8a46a87184fd3ff87432b51a111dfa)). This repository doesn't distribute it. If you redistribute a patched APK, BitChord's own license obligations apply.

The build and test tools (Morphe, Kotlin, the JDK, R8, the Android SDK, Media3, Robolectric, JUnit and others) are downloaded separately and are covered by their own licenses. The versions are listed in `PROVENANCE.json`, and the [Apache 2.0](licenses/APACHE-2.0.txt) text is included.
