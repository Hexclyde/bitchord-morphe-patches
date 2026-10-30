# Publishing 1.1.0-beta.9

This is a testing build for BitChord 1.9. The patch bundle version is
**1.1.0-beta.9**; BitChord's application version stays 1.9.

For local phone testing, import `bitchord-patches-1.1.0-beta.9.mpp` into Morphe
as a Local source. Select the original BitChord APK for the phone's architecture.
Keep the existing Morphe signing key when updating a patched installation.

The publishing ZIP, `bitchord-morphe-patches-1.1.0-beta.9.zip`, uses the same
extract-and-run layout as beta.8. It contains the committed source in
`changes.bundle`, `publish-testing.ps1`, its package manifest, release notes and
`release-assets/` with the built MPP, both Morphe metadata files and SHA256SUMS.
The MPP is already built. No local compilation, WSL or Python is needed to publish
this package.

## Publish the testing build

Use your existing clone of `Hexclyde/bitchord-morphe-patches`, with Git and GitHub
CLI installed and `gh auth login` completed. Save, commit or stash existing
changes first, including untracked files. Download the ZIP into your Downloads
folder, then run these commands in PowerShell:

```powershell
Expand-Archive -Path "$HOME\Downloads\bitchord-morphe-patches-1.1.0-beta.9.zip" -DestinationPath "D:\Bitchord\pkg-119" -Force
cd D:\Bitchord\bitchord-morphe-patches
powershell -ExecutionPolicy Bypass -File "D:\Bitchord\pkg-119\publish-testing.ps1" -Folder "D:\Bitchord\pkg-119"
```

The script verifies the package, imports its committed source into a temporary
Git ref, checks that it extends the current main and testing histories, then asks
once before publishing. Answer `y` to push `testing`, create `v1.1.0-beta.9` and
publish its GitHub pre-release with the four release assets. It verifies the
published MPP against the supplied checksum. The push uses normal fast-forward
rules and never forces a branch or overwrites an existing tag or release.

Your checked-out branch and working files stay in place, matching the beta.8
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

The publishing ZIP includes the ready-built MPP and the Git history needed to
import this release into an existing clone. Its source can be reviewed through
the imported Git commit before the publishing prompt.

## Qualification

The release checks cover generated metadata, compilation, local tests and patched
APK structure/signatures/native inputs for the three supported ABIs. Those checks
do not qualify real-phone output switching, decoder/offload behavior, sleep-timer
interruptions, DJ queue changes, JamesDSP integration or live network services.
Phone testing is still required before treating this testing build as stable.
See the [known limitations](KNOWN_LIMITATIONS.md) and
[download testing guide](SOULSEEK-TESTING.md).

## Compatibility-check branch

The imported work also includes the compatibility-check fix on testing. The ZIP
may retain its separate `watch-fix` Git ref for review; `publish-testing.ps1` does
not push that ref or update main. That separate maintenance change is not needed
to publish the beta.9 patch bundle.

BitChord 1.9 is the application target for this release. The original split
APKs cover arm64-v8a, armeabi-v7a and x86_64. The Soulseek transfer layer has no
32-bit x86 runtime; when selected on the 1.9 universal APK, it removes that
unsupported native directory and keeps the three complete supported ABIs.

The Soulseek, debrid and peer-transfer controls remain under **Patches & tweaks**.
Provider controls start collapsed, use the page's themed switch and settings
gear, and enable only after an API key has been saved securely.


Beta.9 adds adaptive initial Soulseek results and opt-in compatible-peer discovery
with bounded PEX. It does not automatically trust discovered keys or share files.
See the [release notes](RELEASE-1.1.0-beta.9.md) and
[discovery guide](PEER-DISCOVERY.md) before device testing.
