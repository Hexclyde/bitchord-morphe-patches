# 1.1.0-beta.5

First-time download setup now asks where copies go: a phone folder, the user's
configured private Telegram main channel, or both. Users choose enabled audio
formats and arrange their preference order. Downloads retain the settings chosen
when they were queued and validate the completed file's encoding before saving.

The optional Soulseek patch remains download-only. Paired phones can explicitly
share selected files through encrypted Nostr coordination, direct UDP/QUIC, and
available I2P, Tor or IPFS fallback routes. Verified chunks and independent copy
receipts support recovery without silently substituting another recording.

Optional Real-Debrid, AllDebrid, Premiumize and TorBox link downloads are included
in this same build. Each service is independently enabled, starts off,
and uses the user's own protected API key. A chosen service resolves a supplied
HTTPS link or magnet; selected audio files use the saved Phone, Telegram main
channel or Both destination. These services do not provide a music catalogue or
automatic paid-provider fallback. See [debrid setup](DEBRID.md).

The new runtime libraries are included for arm64-v8a, armeabi-v7a and x86_64 on
Android 8 or newer. This is a testing build for BitChord 1.8.1. Local checks pass;
public-network operation and the new workflows still need real-phone testing.
Provider fixtures do not establish live debrid account access or queue behavior.
Collection-level album/playlist integration remains pending. Ordinary Soulseek
clients do not gain the paired-phone overlay protocol automatically.
