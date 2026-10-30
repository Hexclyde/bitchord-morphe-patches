# 1.1.0-beta.11 testing build

All four beta.10 implementation stages are present: player-shell Downloads,
batch source selection, selected-file public Soulseek uploads and verified
Telegram offloading with restore. See [the usage and boundaries guide](DOWNLOAD-MANAGER.md).

The source contains beta.9 and beta.10 in its Git history. The patch bundle is for
the original BitChord 1.9 APK; it does not rename the app's upstream version.
Install/apply the MPP through Morphe. Telegram archive features also require the
optional Telegram patch and the user's configured private main channel.

The package contains the MPP, checksums, mobile-source metadata, a full source
Git bundle and publishing instructions. Nothing was pushed to GitHub or published
as a release during this local build. Verify the original APK hash before patching.

Live-device, real remote-peer and real Telegram account qualification must still
be performed on a connected device. Automated tests use loopback peers and
service fixtures, with real production state machines and Android views.
