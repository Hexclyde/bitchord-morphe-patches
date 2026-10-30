# Unofficial Soulseek audio on BitChord 1.9

Compatibility bundle: `1.1.0-beta.7-soulseek.1`.

This is an integration snapshot, not the combined testing-branch release.
The expected `1.1.0-beta.8` release will be assembled after the other patch
updates are supplied and integrated.

This update targets **Unofficial Soulseek audio** and the shared settings entry
that it needs. It retains BitChord 1.8.1 support and adds the released BitChord
1.9 phone APKs on Android 8 / API 26 or newer, for arm64-v8a, armeabi-v7a and
x86_64. The other patches keep their existing 1.8.1 compatibility declarations.

The 1.9 universal APK is also checked. Its unsupported 32-bit x86 native
directory is removed so Android selects an ABI with all download runtimes.
The resulting APK supports the three architectures above; it does not support
32-bit x86 devices.

## Using the built patch

Import the included `bitchord-patches-1.1.0-beta.7-soulseek.1.mpp` into Morphe.
For a BitChord 1.9 APK, select **only Unofficial Soulseek audio**. Leave the
Telegram, Install as Bitchord+, MicroG and optional playback patches unselected.
They are being updated separately. The standalone Soulseek patch retains the
original application ID; a differently signed patched APK cannot update an
already installed official APK in place.

The patch remains a download feature. It includes the independent Real-Debrid,
AllDebrid, Premiumize and TorBox toggles and API-key settings, together with the
direct UDP / QUIC, Tor, I2P and IPFS transfer runtimes. Existing download
destinations and format preferences remain under **Settings > Patches & tweaks**.
Configure provider credentials inside the app. Existing peer-pairing and
transport setup requirements still apply.

Use **Phone** as the copy destination for standalone 1.9 testing. Telegram or
Both requires the separately updated Telegram patch in the combined release.
This update does not port the Telegram account or upload implementation.

## Importing the source alongside other patch work

The ZIP contains a scoped `compatibility.patch`, its checksum manifest and an
import script. It can merge the compatibility changes into a clean checkout
based on the completed beta.7 source or a later descendant. It checks the merge
in a disposable local checkout before applying it to the current branch. A
conflict stops the import before the current checkout is changed.

After downloading the compatibility ZIP, run:

```powershell
Expand-Archive -Path "$HOME\Downloads\bitchord-soulseek-1.9-compatibility.zip" -DestinationPath "D:\Bitchord\pkg-soulseek19" -Force
cd D:\Bitchord\bitchord-morphe-patches
powershell -ExecutionPolicy Bypass -File "D:\Bitchord\pkg-soulseek19\import-soulseek-1.9.ps1" -Folder "D:\Bitchord\pkg-soulseek19"
```

The import stages the changes for review. It does not commit, push, publish a
release, replace another branch or rewrite repository history. Review with
`git diff --cached`, then commit the merged result on the branch used for the
other patch work. If the existing work changed one of the same lines, resolve
that integration in the source repository rather than replacing its files with
the packaged versions. The source import does not change the repository's
release version or generated patch catalog; regenerate those for the combined
release after integrating the other patch work. The built MPP represents this
compatibility snapshot; rebuild after integrating additional patches.

## Changes and verification

The settings hook handles the main-page category list introduced in 1.9 and the
R8 relocation of the Sources screen. Download hooks validate the actual
optimized method layouts, and the route adapter supports the new constructor
while retaining the 1.8.1 form. Checks cover the standalone selection so that the
Telegram account implementation is not pulled in as a dependency.

Provider controls follow the existing page's theme, switches and settings gear.
They start collapsed; enabling a provider expands its settings and closes the
previous provider. A disabled provider hides its controls and disables the gear.
First enable asks for an API key and waits for a successful encrypted save.

Verification passed on seven standalone APK cases: all three supported split
APKs for 1.8.1 and 1.9, plus the 1.9 universal APK. The 26 Android UI tests cover
the nested debrid controls, dark and light themes, format ordering and the
parent Patches & tweaks controls. The host regression suite and 30 Python
tests also passed. API fixtures cover all four providers without real accounts.

Reproduce APK checks after building with:

```bash
python3 tools/validate_soulseek_19.py --inputs /path/to/pinned/apks
```

The original 1.9 APK checksums are recorded in
`resources/soulseek/compatibility-1.9.json`. Host-side tests
and APK inspection do not qualify real provider accounts, NAT traversal, public
overlay networks or Android device lifecycle behavior. Those still require
on-device testing with the user's own configuration.
