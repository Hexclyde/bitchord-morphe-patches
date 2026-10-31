# Publishing 1.1.0-beta.16

The ready-built ZIP contains the beta.16 MPP, generated Morphe source metadata/checksums, committed Git bundle, updated interactive installation guide and a PowerShell publisher. The production Downloads, player-content and Telegram opening fixes are included alongside both beta.14 branches and all earlier improvements.

For phone testing, import `release-assets/bitchord-patches-1.1.0-beta.16.mpp` as a Local source in Morphe, select original BitChord 1.9 for the phone's architecture, and keep the existing Morphe signing key. Choose **Streamseek previews** to include its Soulseek dependency. Enable Soulseek and the Streamseek previews capsule switch in Patches & tweaks; the preview switch starts disabled.

To publish, extract the complete ZIP. From your clean existing clone of Hexclyde/bitchord-morphe-patches run:

```powershell
Expand-Archive -Path "$HOME\Downloads\bitchord-morphe-patches-1.1.0-beta.16.zip" -DestinationPath "D:\Bitchord\pkg-beta16" -Force
cd D:\Bitchord\bitchord-morphe-patches
powershell -ExecutionPolicy Bypass -File "D:\Bitchord\pkg-beta16\publish-testing.ps1" -Folder "D:\Bitchord\pkg-beta16"
```

Git and GitHub CLI with an authenticated account are required. The script imports the exact packaged commit into a temporary ref, checks hashes and main/testing ancestry, and asks once before pushing testing, the new version tag and its pre-release. It does not force branches or overwrite releases. The ready-built package requires no local compilation or WSL. No GitHub release has been published during package preparation.

GitHub Pages remains a manual repository setting: branch testing, folder /docs. The offline interactive guide is in docs/guide.

The source includes 19 public patches. Automated checks cover compilation, metadata, Android framework behavior, host regression, three combined ABI APKs, playback-only selection and Streamseek alone and Soulseek without previews. Live service availability and real-phone playback/background behavior still require device qualification. See [Streamseek previews](STREAMSEEK-PREVIEW.md), [FLAC Audio](FLAC-AUDIO.md), [mixed playlists](MIXED-PLAYLISTS.md), and [known limitations](KNOWN_LIMITATIONS.md).
