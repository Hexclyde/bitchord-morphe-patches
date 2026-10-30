# Publishing 1.1.0-beta.5

This is a testing build for BitChord 1.8.1. The patch bundle version is
**1.1.0-beta.5**; BitChord's application version stays 1.8.1.

For local phone testing, import `bitchord-patches-1.1.0-beta.5.mpp` into Morphe
as a Local source. Select the original BitChord APK for the phone's architecture.
Keep the existing Morphe signing key when updating a patched installation.

The publishing ZIP, `bitchord-morphe-patches-1.1.0-beta.5.zip`, uses the same
extract-and-run layout as beta.4. It contains the committed source in
`changes.bundle`, `publish-testing.ps1`, its package manifest, release notes and
`release-assets/` with the built MPP, both Morphe metadata files and SHA256SUMS.
No local compilation is needed to publish this package.

## Publish the testing build

Use your existing clone of `Hexclyde/bitchord-morphe-patches`, with Git and GitHub
CLI installed and `gh auth login` completed. Save, commit or stash existing
changes first, including untracked files. Download the ZIP into your Downloads
folder, then run these commands in PowerShell:

```powershell
Expand-Archive -Path "$HOME\Downloads\bitchord-morphe-patches-1.1.0-beta.5.zip" -DestinationPath "D:\Bitchord\pkg-115" -Force
cd D:\Bitchord\bitchord-morphe-patches
powershell -ExecutionPolicy Bypass -File "D:\Bitchord\pkg-115\publish-testing.ps1" -Folder "D:\Bitchord\pkg-115"
```

The script verifies the package, imports its committed source into a temporary
Git ref, checks that it extends the current main and testing histories, then asks
once before publishing. Answer `y` to push `testing`, create `v1.1.0-beta.5` and
publish its GitHub pre-release with the four release assets. It verifies the
published MPP against the supplied checksum. The push uses normal fast-forward
rules and never forces a branch or overwrites an existing tag or release.

Your checked-out branch and working files stay in place, matching the beta.4
workflow. The script only pushes `testing` and the new version tag. The `main`
branch stays at its existing release. If testing has newer changes outside this
package, the script stops before publishing so those changes are preserved.

If publication fails after the Git push, inspect the remote tag and release
before retrying; the script refuses to overwrite either. A successful source
push can still exist if GitHub release creation fails. The repository's Checks
workflow can run on the testing push, and its mobile-source workflow runs when
the release is published. Check the Actions tab for their results.

Testers use this Morphe source URL:

```text
https://raw.githubusercontent.com/Hexclyde/bitchord-morphe-patches/refs/heads/testing/patches-bundle.json
```

The separate source-only ZIP remains usable for reviewing and building the
project. It excludes Git history, build tools, private data, signing keys, APKs
and compiled libraries. The publishing ZIP includes the ready-built MPP and the
Git history needed to import this release into an existing clone.

## Qualification

The local checks cover generated metadata, Java compilation, host tests and
patched APK structure/signatures/native inputs for the three supported ABIs.
Real-phone validation of the new downloads, public NAT/STUN, I2P/Tor reachability,
live Telegram uploads and Android background behavior is still required before
treating this testing build as a stable release. See the
[download testing guide](SOULSEEK-TESTING.md).
