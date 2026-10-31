# 1.1.0-beta.12 testing build

Combines the full beta.9–11 download implementation with the incoming Audio and video switch, JamesDSP status/setup improvements, README badges and interactive GitHub installation guide.

- Download view: Grid, List, Compact, Comfy and Detailed, saved on the device.
- BitChord 1.9 green capsule switches throughout patch controls.
- Downloads navigation icon appears only with the download add-on patch installed.
- Audio/Video is a saved YouTube preference across tracks and restarts. A missing alternate version leaves the choice unchanged. Telegram and local files are excluded. Party playback is not replaced.
- Existing embedded Downloads, batch choices, public Soulseek inventory and verified Telegram offloading/restore retained.

The incoming branch’s Soulseek-removal changes are intentionally excluded. The guide is staged for GitHub Pages from testing /docs; no Pages settings or GitHub release were changed during preparation. The self-contained UI design lab is an HTML adaptation, not the Android runtime; exact phone appearance and live-account/public-network behavior still need on-device qualification.

See [Downloads](DOWNLOAD-MANAGER.md), [publishing](PUBLISHING-1.1.0-beta.12.md) and [verification](VERIFICATION-1.1.0-beta.12.md).

## Peer downloads

Paired devices (called Trusted peers in the connection setup) are phones whose app identity public keys you explicitly verify and approve. Pairing does not expose phone storage. A sender selects complete files and authorizes the recipient; the receiving phone accepts the offer. Nostr coordinates the offers, while supported direct/overlay routes carry file bytes. Public Soulseek search and selected-file public uploads are separate and do not require pairing every public Soulseek user.
