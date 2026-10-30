# Soulseek and paired-phone downloads

The optional Morphe patch adds on-phone audio downloads to BitChord 1.8.1 on
Android 8 or newer, for arm64-v8a, armeabi-v7a and x86_64. Soulseek results and
paired-phone files are downloaded completely and verified before saving. They do
not supply live playback or automatically replace YouTube. Completed phone copies
can be played through the normal local-file workflow.

Development branch: `feature/soulseek-audio`, based on `testing` commit
`3de260f7f1d7967c5a97ce924085d7d42bec18af` (1.1.0-beta.4).
The local testing bundle is `1.1.0-beta.5`; it has not been published.
See [testing instructions](SOULSEEK-TESTING.md).

## Current implementation

| Feature | Behavior |
| --- | --- |
| Soulseek | Account setup, manual Addons search and download of the exact selected peer result |
| First setup | Choose Phone, Telegram main channel or Both, then enable and order audio formats |
| Paired sharing | Explicitly select complete local audio files; send offers to saved app public keys |
| Coordination | Signed, encrypted Nostr offer, recipient request and source grant |
| Direct path | Retained-socket public STUN discovery, reciprocal UDP punching and certificate-pinned QUIC |
| Fallback paths | Embedded I2P streams, Tor onion streams and paired IPFS Bitswap |
| Transfer state | Per-item journals, verified chunk checkpoints, pause, cancel and explicit restart recovery |
| Publication | Byte-preserving phone files and confirmed original-document Telegram uploads |

The runtimes execute inside the patched app. No VPS, custom gateway, bot or
companion app is required. Public STUN servers, Nostr relays and the existing
Soulseek, I2P, Tor and Telegram networks still provide infrastructure. The
Soulseek patch works without the Telegram patch when Phone is selected.

The build also includes [optional debrid downloads](DEBRID.md). Those use
an explicitly selected service account to resolve a supplied link or magnet and
save selected audio through the same destination publisher. Their keys and cloud
transfers are separate from Nostr pairing and native transport fallback.

Album/playlist catalog integration and automatic compatible-provider discovery
remain unfinished. The current sharing picker accepts multiple selected files
and processes them as individual queue items; it does not infer an album from
filenames or find an alternate recording after failure.

## First setup and formats

**Where copies go** requires an explicit destination. Phone uses a SAF folder
with persisted read/write permission. Telegram uses the signed-in account's
configured private main channel and requires the Telegram integration. Both
records each copy's result independently. Jobs keep their destination snapshot;
changing settings cannot redirect queued work to a different account or folder.

**Formats, best first** enables and orders FLAC, ALAC, WAV, AIFF, DSD, Opus,
AAC, Vorbis and MP3. Filename extensions provide search hints. Completed files
are inspected for their actual encoding before publication. Container names can
be ambiguous: an M4A may contain ALAC or AAC, and Ogg may contain Vorbis or Opus.
Changing preferences does not rewrite the policy captured by an accepted job.
Encoding checks do not prove the artist, recording, mastering history or an
original lossless source.

## Two source paths

An explicit Soulseek result is intercepted before BitChord's filename-based
download reuse. The source must be enabled and the host's current download
settings must allow downloads. The task binds the selected catalog row and login
session, copies the complete source into private staging, validates its encoding
and imports the original bytes into the destination publisher. It does not
retag the file or repeat a title search. An expired result or changed session
fails closed.

The initial Soulseek capture is tied to the current login session. Process death
before the completed-file import requires selecting the result again; interrupted
private capture files are cleaned before a later task. Once imported, the saved
publication job can be recovered from its journal.

For a paired phone, the sender copies only explicitly selected local files into
immutable private sharing storage. A signed Nostr offer binds its trusted app key,
opaque file ID, length, full-file SHA-256, manifest root, authorization token and
available routes. The recipient explicitly approves the file and its destination
snapshot before requesting a grant. Source grants match the peer, file, session,
request nonce and recipient IPFS identity. Direct candidates travel inside the
same encrypted request.

Ordinary Soulseek clients do not advertise these paired-phone routes. Their files
do not acquire I2P, Tor or IPFS fallback merely because this patch is installed.

## Direct and fallback transports

The paired-download order is Direct, I2P, Tor, then IPFS. Only routes present in
the authenticated offer are tried. Failed connections, unavailable runtimes and
corrupt responses can advance to the next route. Every accepted manifest and
block must still match the same signed file identity; fallback cannot substitute
a different original or append unverified bytes.

| Route | Implementation and limits |
| --- | --- |
| Direct | STUN and QUIC share the same retained IPv4 UDP socket. Nostr carries endpoint candidates and the provider's certificate; the receiver pins that certificate. Reciprocal punching precedes connection attempts. Restrictive NATs can still prevent a connection. |
| I2P | Embedded i2pd with authenticated reseed certificates and reliable I2P streams. A non-exported private Android service process owns the router. |
| Tor | Bundled Guardian Tor, authenticated local control and an ephemeral v3 onion service. A separate non-exported process owns the runtime; no Ricochet desktop client is included. |
| IPFS | Standard Bitswap over libp2p Noise/Yamux/TCP, restricted to paired peers and per-file publication grants. It needs a reachable provider endpoint. |

IPFS currently has no public DHT, mDNS, gateway, relay or DCUtR. It is not a
universal NAT traversal fallback and does not provide Tor/I2P anonymity. Direct
connections disclose endpoint IP addresses to the paired phone. Switching to
another route cannot retract an address already disclosed.

Source runtimes are started by explicit file sharing so their endpoints can be
offered. I2P and Tor use visible foreground services while running. Recipients
start eligible runtimes as needed. Stopping an isolated router process cannot
terminate the player process. Network changes invalidate direct socket state;
fresh offers may be needed after reconnecting.

The main download foreground service also covers direct/IPFS transfers and
selected Soulseek captures. Explicitly started download coordination keeps its
notification active while waiting for peers, until Stop or an Android background
time limit. It does not start automatically at boot. Work without active
coordination, providers or transfers can stop after an idle interval.

## Verification, storage and recovery

Paired files use raw SHA-256 CIDv1 blocks of at most 256 KiB and a strict manifest
containing each block's hash and length. The manifest hash is bound to the signed
offer. Every received chunk and the complete file are verified. A CID authenticates
bytes, not their recording metadata.

Per-item journals retain the source binding, destination, format choices and
verified checkpoint. Recovery rechecks persisted data and discards an unverified
tail. One bulk writer owns staging at a time; route changes cannot append from
two writers concurrently. Restarted jobs wait for explicit Resume. Pause and
Cancel stop active I/O and prevent old work from publishing a result.

Each source file is limited to 256 MiB. Application sharing/download staging is
bounded separately from the IPFS native store, which can retain up to 512 MiB of
blocks in addition to application staging. Soulseek has its own bounded transfer
cache. Publication and Telegram upload can require additional temporary space.
Insufficient storage is reported before a new complete file is accepted.

Only explicitly published files are served. IPFS received blocks use a private
cache and are not automatically seeded. Per-file grants and publication leases
end on runtime restart; selected files are not silently re-published. Revoking a
share removes its serving capability, but cannot retract bytes already sent.
Saving into a Telegram channel never exposes that channel to paired phones.

## Publication

Phone publication creates a new SAF document, writes the verified original and
reads it back to check its length and full-file SHA-256 before reporting success.
It does not adopt an unrelated file with the same name or modify audio tags.
Interrupted document creation has a recovery state rather than blindly creating
another copy.

Telegram publication pins the account ID and owned private channel ID. Uploads
use original documents without transcoding. A durable send receipt binds the
file name, length, hash and destination. Pending TDLib sends are reconciled with
send updates and channel results; uncertain delivery is reported for inspection
instead of blindly posting a duplicate. Completion requires a confirmed message.
These account and channel identifiers never enter peer offers.

With Both, a successful phone copy remains complete if Telegram fails. Resume
retries the unfinished destination using the same verified original. The manager
shows phone and Telegram status separately.

## Coordination and qualification

[Nostr signaling](NOSTR-SIGNALING.md) uses dedicated app keys and explicitly saved
peers. Nostr relays carry offers, authorization and small endpoint descriptors;
audio bytes travel over the selected download route. Relays are not a music
index. A transfer fallback does not restore a failed signaling channel, and a
new grant requires a working shared relay.

The four native transports are built for all three supported ABIs. Local
protocol, JNI, stream and transfer fixtures check framing, authorization,
corruption, cancellation, chunk recovery and publication behavior. Public Tor
and I2P bootstrap, real carrier/router NAT traversal, Android background behavior,
Keystore operation and live Telegram delivery still need phone qualification.
Compilation and loopback success do not establish public-network reachability.

## Sources and licenses

The Soulseek JNI engine uses [soulseek-rs](https://github.com/michel/soulseek-rs)
revision `d20e433a8979068acd80f4d4242125beaf4825ee`, with embedded-mode changes in
[vendored provenance](../vendor/soulseek-rs-lib/PROVENANCE.json).
The adapter is GPL-3.0-or-later. Notices and locked dependency inventories are in
[third-party notices](../THIRD_PARTY_NOTICES.md),
[Soulseek native licenses](../licenses/soulseek-native-dependencies.txt),
[I2P runtime sources](../native/i2p/README.md),
[Tor runtime sources](../native/tor/README.md) and
[IPFS runtime sources](../native/ipfs/README.md).

[SeekerAndroid](https://github.com/jackBonadies/SeekerAndroid) at
`b2dda3d552186071744f29014b635264d1867187` and
[StreamSeek](https://github.com/ndr-brt/streamseek) at
`851efcfcbf66e8b3216191d56616af378e9573b0` were references; their applications
and .NET/Node runtimes are not bundled. Ricochet Refresh is a Tor design reference.
