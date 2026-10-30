# Download connection coordination

The Soulseek patch includes opt-in Nostr coordination between explicitly paired
phones. It uses a separate generated identity and does not send Telegram messages.
It carries encrypted peer checks, selected-file offers, recipient authorization
and direct UDP candidates. File bytes use the direct or fallback transport;
Nostr does not discover music or carry the audio. Beta.9 also adds a separate,
opt-in public profile scan to find compatible app keys; see
[peer discovery](PEER-DISCOVERY.md). Public keys still require explicit pairing.

## Setup

1. Open **Settings → Patches & tweaks**, enable **Peer downloads**, then open
   **Connection setup** from its expanded controls.
2. Exchange the displayed public keys through a trusted channel. Compare the full
   key before adding it to **Trusted peers** on the other phone.
3. Configure one to three secure relay URLs on both phones. They need at least
   one common working relay. The initial addresses are editable examples, not a
   guarantee that an operator will accept this application protocol.
4. Save settings, then start coordination on both phones.
5. Enter a paired phone's public key and select **Test trusted peer**. A successful
   check requires an authenticated reply; a connected relay alone is insufficient.
6. Open **Peer downloads** and start download connections on both phones. The
   sender chooses local audio files and the recipient explicitly approves an
   incoming offer. First setup must have saved copy destinations and audio formats.

Starting **Peer downloads** connections uses a visible foreground notification
while coordination is waiting for peers or transfers are active. The standalone
connection-test screen continues only while BitChord's process remains alive.
Neither path starts automatically on launch or survives process termination.
Use **Stop coordination** or the download notification's Stop action to disconnect.
Resetting the identity removes its trusted peers; exchange the replacement public
key before reconnecting. Android background time limits can require an explicit
restart and Resume.

## Privacy

Private keys and settings are encrypted with Android Keystore and stored outside
Android backups. The setup screen exports only the public key. A peer key is not
proof of a person's name or ownership of a Soulseek account.

Relay operators can observe the connecting IP, application public keys, recipient
tags, timing and encrypted event sizes. NIP-44 does not provide forward secrecy.
Application expiration rejects stale messages; it does not guarantee that a relay
or recipient deletes previously received ciphertext. A direct file
connection also exposes its network endpoint to the other phone.

Telegram remains an independent storage/upload integration. Telegram account IDs,
phone numbers, channel identifiers and account credentials are not part of this
signaling protocol. No audio is uploaded to Nostr relays.

## Role in downloads

Soulseek supplies manual searches and ordinary peer downloads. Separately, two
paired phones can share explicitly selected complete local files through the
durable download manager. Neither path supplies live playback or automatically
replaces a YouTube source.

Nostr carries a compatible provider's signed, encrypted offer: an opaque object
identity, expected original-file hash, manifest reference, supported transports
and expiring connection details. The receiving phone selects an eligible direct,
I2P, Tor or IPFS download route for that object. I2P destinations and onion
addresses identify services; an IPFS offer identifies a CID/profile and provider.
Changing route preserves the authenticated content binding and chosen destination.
The completed original is verified before phone publication or Telegram upload.

The sender offers a file to one saved peer key. The recipient snapshots its copy
destinations and format choices, then sends a request containing a fresh nonce,
its IPFS identity and optional direct endpoint descriptor. The source matches the
peer, file and signaling session, grants that IPFS peer access to the specific
publication and punches toward the authenticated receiver's UDP candidates. Its
reply echoes the request nonce and recipient identity. Only a matching grant
queues the receiver's download. A failed direct punch can still permit fallback.

The direct descriptor contains a bounded IPv4 candidate list and the provider's
exact certificate. STUN discovery and QUIC use the same retained UDP socket.
Both phones open their mappings, then the receiver attempts certificate-pinned
QUIC. This is a bounded direct connection path, not a full ICE/TURN implementation;
some carrier-grade or endpoint-dependent NATs will still prevent it. The download
order is Direct, I2P, Tor, then paired IPFS, skipping routes not in the signed offer.

Ordinary Soulseek peers do not advertise this protocol. They cannot acquire
alternate routes automatically. The opt-in public discovery screen finds
compatible app keys; binding their offers to selected recordings remains a
catalog task. Nostr relays are not an existing music index. A transfer-route
fallback does not repair a failed signaling channel: it needs a valid cached offer or another established way to
reach the authenticated provider.

The IPFS adapter uses paired Noise/TCP Bitswap with per-file grants. It does not
enable public DHT, relays or DCUtR, so it needs a reachable provider and cannot
guarantee another route through restrictive NAT. I2P and Tor provide their own
network paths; they do not turn ordinary Soulseek peers into compatible providers.

## Wire format

The transport uses NIP-01 signed ephemeral events of kind `21118`, with NIP-44 v2
encrypted JSON content. It is a BitChord application protocol, not NIP-17.
The envelope identifies `app.bitchord.signal/1` and binds sender, recipient,
session, message type, creation time and expiration to its encrypted payload.
Messages expire after 120 seconds. Only configured peer keys are accepted.

Allowed message types are `hello`, `offer`, `answer`, `candidate`, `cancel`,
`ping`, `pong`, `pex_request` and `pex_response`. The two PEX messages are sent
only to already trusted peers after a compatible `app.bitchord.pex/1` hello.
Their bounded payloads carry original signed public profiles, never private offers
or grants. Older clients retain the existing hello/file-transfer behavior.
Session IDs are 32 lowercase hexadecimal characters; payloads are JSON objects limited to 8 KiB. The engine bounds queued commands, incoming
messages, replay history, peer traffic and WebSocket frames. It rejects malformed,
untrusted, expired, replayed or incorrectly signed events before delivery.

File messages use an inner `app.bitchord.bulk-signal/1` envelope. `offer` carries
the immutable `app.bitchord.bulk/1` file metadata; `answer` carries `request` or
`grant`, and `cancel` revokes a pending offer. Approval sessions are bounded to
two minutes and are cleared when coordination stops. Already accepted durable
jobs retain their authenticated file binding. Expiration does not revoke bytes
or capabilities already delivered to an authorized recipient.

Public STUN candidates are configured separately from signaling. The candidate
source is [mondain's list](https://gist.github.com/mondain/b0ec1cf5f60ae726202e),
revision `2d2b96b4508a38d342e0228d46eab84dad2398a3`. The parser bounds imports,
deduplicates entries and selects at most three hosts from the bundled snapshot
when the user starts a direct runtime. The list is not downloaded at startup.
Google and Cloudflare endpoints are retained as candidate defaults if the bundled
snapshot cannot be read. Discovery sends bounded STUN requests, retains the
mapped socket for QUIC and stops direct I/O when the active network changes.

## Public app discovery and PEX

Public provider profiles use replaceable NIP-01 kind `11120` and the exact
`app.bitchord.providers/1` topic, independently of the encrypted kind `21118`
channel. The replacement kind lets a new subscriber read a relay's most recent
fresh profile; it does not depend on an ephemeral event arriving during a scan.
The app enforces signatures, strict fields/tags and a validity period of at most
300 seconds. A profile advertises supported transport names, not endpoint
addresses, files or current reachability. Publishing is a separate explicit
opt-in and ends when discovery is stopped or its screen leaves the foreground.
Relays can retain previously published data after local shutdown or expiration.

A ten-second scan can start with zero paired peers. PEX supplements it only when
an existing paired connection negotiates support. Each response contains at most
eight signed public profiles within the existing 8 KiB encrypted payload limit,
with per-peer request/response rate limits, expiring nonces and bounded caches.
Receiving a referral does not save a trusted key or create a download session.
This is the app's provider-referral protocol, not BitTorrent `ut_pex`, and it does
not change the private IPFS node's DHT policy. The [discovery guide](PEER-DISCOVERY.md)
explains setup and limitations.

## Relay discovery and selection

The candidate pool merges the existing relay defaults with
[sesseor's list](https://github.com/sesseor/nostr-relays-list/blob/main/relays.txt),
pinned at `23234b5d167ba7a482b4d3d2a523e7b1f0d69050`. It contains 1,746 deduplicated
WSS candidates supported by the current direct-relay client. Source hashes and
filter counts are recorded in `resources/soulseek/nostr-relays-source.json`.
STUN servers remain a separate UDP endpoint-discovery list.

**Find best relays** is an explicit on-phone check. It samples at most 24
candidates with two workers and a two-minute budget, combining configured
relays, recent results and rotating exploration. It never scans the entire pool
at startup. Another check explores additional candidates.

A usable result requires connection, subscription, three signed encrypted
self-message round trips and matching write acknowledgements. Socket connection
alone is reported separately. Successful candidates are ranked by measured median
round-trip time plus a smaller connection-setup penalty. This measures the
user's actual path to the relay, including geography and ISP routing, without
GPS, GeoIP requests or location permissions. It measures coordination latency,
not audio throughput or delivery to a different peer.

Measurements are cached for 24 hours and bound to the app identity, boot/session
and active network. Network or identity changes cancel a running check and
invalidate its recommendations. Apply is explicit and preserves all working
configured relays before filling free slots, up to three active relays. Paired
phones still need a common working relay; replacing established relays requires
coordinating the change with the other phone and checking paired delivery.

Regenerate the pinned candidate snapshot with:

```bash
python3 tools/update_nostr_relays.py --offline --check
```

## Checks

Native tests use a local relay fixture rather than public accounts or messages.
They cover two-session delivery, authentication failures, message bounds,
expiration, replay, cancellation and shutdown. Application tests also cover
offer consent, session/source/file/nonce binding, IPFS grants, direct candidate
bounds, destination/format snapshots and duplicate-grant rejection. Direct
transport tests cover real loopback QUIC and the same-socket STUN boundary.
Android Keystore operation, public-relay acceptance, real NAT traversal and device
lifecycle still require phone testing.

```bash
python3 tests/soulseek/run.py
python3 tests/coordination/run.py
python3 tests/bulk-coordination/run.py
python3 native/direct/test_runtime.py
cargo test --locked --manifest-path native/soulseek/Cargo.toml
```

References: [NIP-01](https://github.com/nostr-protocol/nips/blob/master/01.md),
[NIP-44](https://github.com/nostr-protocol/nips/blob/master/44.md),
[NIP-40](https://github.com/nostr-protocol/nips/blob/master/40.md).
