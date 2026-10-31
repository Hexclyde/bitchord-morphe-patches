# 1.1.0-beta.15 testing build

Combines both beta.14 source branches and retains all 19 selectable patches, with complete source history.

- Fix Downloads navigation: use AndroidView's default modifier when the optimized host passes null, and keep rendering inside LibraryScreen's balanced Compose restart group and original recomposition callback.
- Restore the Audio/Video control after every actual player-window open, close and recreation. Keep the user's saved YouTube mode across tracks.
- Retain Streamseek MP3/FLAC previews, reuse the selected transfer for immediate/queued downloads, compare excerpts with a known local reference, inspect complete-file SHA-256 and export evidence.
- Handle Telegram page startup/render failures without closing BitChord. If Android refuses overlay protection, fail before credential entry and show a diagnostic screen. Copy crash details includes exception classes and stack frames, excluding exception messages and account/API values. Handle a missing error detail safely. This fixes reproduced failure paths; the reported phone failure has no trace, so its exact cause and resolution remain unconfirmed.
- Preserve the beta.11 interactive guide and six README badges. The full repository README is also the ZIP's README; short installation/publishing instructions are in INSTALL.md. Open-Guide.html opens the bundled offline guide, updated for beta.15 and all 19 patches.

Automated results and tested APK selections are recorded in verification.json. No phone, real Telegram account or public Soulseek peer qualification is claimed. Mornye is not included. Nothing is pushed or published to GitHub by preparing this package.

Repatch the original BitChord 1.9 APK with the same selections and existing Morphe signing key, then update the installed patched app. Keep Streamseek previews selected if you use it; its in-app toggle starts off on a first install. No data-clearing or reinstall step is required by these changes.
