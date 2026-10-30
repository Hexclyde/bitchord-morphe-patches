# Paired IPFS Bitswap adapter

This is a real libp2p 0.56 / Beetswap 0.5 download transport, not an HTTP gateway
or an IPFS protocol imitation. It uses the standard `/ipfs/bitswap/1.2.0` protocol
over authenticated Noise + Yamux + TCP. No DHT, mDNS, bootstrap, DNS dialing,
public gateway, public relay, DCUtR or QUIC is enabled. A reachable provider
IP/TCP endpoint and libp2p PeerID must arrive in an authenticated Nostr offer.
Listeners bind a bounded snapshot of local IPv4 interfaces; recreate the runtime
when the network changes. If the OS forbids interface enumeration, the node
keeps a loopback listener and outgoing dialing, and reports `listenWarning`.
Such a node must not advertise inbound Internet reachability.
It does not by itself make an unreachable NATed provider reachable, and it does
not provide Tor/I2P anonymity. Public-relay NAT traversal is not advertised.

## JNI contract

`IpfsNative` calls are blocking worker-thread operations. Errors are Java
`IOException`s. Handles are registry IDs, not pointers; at most four runtimes
can be open. The identity is an app-private persisted Ed25519 libp2p key and is
distinct from the Nostr identity. The app must cryptographically bind the two
identities in its signed, encrypted Nostr control messages.

- `create(home, peerIdsJson)` starts a node. The allowlist defaults closed.
- `info(handle)` returns `peerId`, `listenAddresses`, and capability flags.
- `setPeers(handle, peerIdsJson)` replaces at most 16 paired PeerIDs.
- `connect(handle, peerId, addressesJson, timeoutMs)` dials 1–8 explicit IP/TCP
  multiaddresses, with an optional matching `/p2p/<peerId>` suffix.
- `publish(handle, absoluteSourcePath)` snapshots a selected regular file of at
  most 256 MiB into raw blocks, with **no authorized recipients**.
- `grantPublication(handle, manifestCid, peerIdsJson)` replaces the authorized
  recipients for that file. Grant only after the application's Nostr file
  authorization succeeds, and before telling the recipient to request blocks.
- `fetchBlock(handle, peerId, cid, timeoutMs)` fetches one verified raw block at
  a time. `timeoutMs` is 100–120000. The peer must be paired and connected for a
  cache miss. Bitswap may ask other connected paired peers for the same CID;
  CID verification authenticates the bytes. Do not use PeerID as a substitute
  for the signed manifest/file binding.
- `unpublish(handle, manifestCid)` removes the publication and all its grants.
- `clearPrivate(handle)` removes only unpublished cached blocks and returns
  bytes freed. It never removes blocks needed by an active publication.
- `cancel(handle)` interrupts the active fetch/publication. `close(handle)`
  invalidates the handle, cancels queries and shuts down the actor.

`publish` returns:

```json
{"manifestCid":"bafk...","manifestSha256":"hex","manifestJson":"exact UTF-8 JSON","manifest":{"version":1,"blockSize":262144,"size":123,"sha256":"hex","blocks":[{"cid":"bafk...","size":123,"sha256":"hex"}]}}
```

Manifest field order is exactly `version,blockSize,size,sha256,blocks`; each
block's field order is `cid,size,sha256`. There is no whitespace or newline.
Both files and manifests use CIDv1 raw (`0x55`) + SHA2-256 (`0x12`) CIDs. Every
data block is at most 262144 bytes. The manifest is itself a raw block, fetched
through `fetchBlock(manifestCid)`. Full-file and block SHA256 are lowercase hex.
Verify the signed manifest root, strict structure, file size and full-file hash
in the Java download engine before committing the download.

## Privacy, limits and lifetime

Publication views are read-only. Beetswap's automatic received-block put is
intentionally rejected because upstream otherwise forwards new bytes directly
to waiting peers. The actor separately verifies responses into a private cache.
Received blocks are therefore never implicitly seeded, even when a paired
peer already has an outstanding want. A server policy checks the requesting
PeerID and CID both before storage lookup and before dispatching a response.
Only blocks belonging to a publication granted to that specific peer are served.
Already transmitted data cannot be revoked. Publication leases and grants end
on runtime restart; cached bytes and identity persist.

The native store has a hard 512 MiB / 2048 block limit, with at most 16 live
publications and a 256 MiB maximum source file. Failed/canceled snapshots can
leave private cache blocks; call `clearPrivate` to reclaim them. Native snapshots
duplicate application staging, so account for both quotas. Maximum native store
usage is 512 MiB in addition to the application's source/download quota.

The node allows eight connections, one per peer, eight pending inbound/outbound
connections, two inbound Bitswap streams per connection, eight wants per peer,
eight server read batches, and 32 queued outgoing blocks. The application should
keep a single provider connection per download runtime to avoid unnecessary
want propagation. No public peer network tests are performed by this project.

## Reproducible build and local verification

```sh
python3 tools/build_ipfs_native.py --offline
cargo test --locked --manifest-path native/ipfs/Cargo.toml -- --test-threads=1
```

The build uses at most two Cargo jobs and Android API 26. ELF machine/class and
16 KiB LOAD alignment are checked for arm64-v8a, armeabi-v7a and x86_64. Outputs
are `.deps/ipfs/<abi>/libbitchord_ipfs.so`, `build-hashes.json`, dependency
inventory and license texts. `Cargo.lock` pins all dependency checksums, including
upstream-required `core2 0.4.0` (yanked upstream; its locked checksum is retained).

The real loopback tests cover multi-block transfer and manifest retrieval,
persisted identity, unpaired denial, per-file grants, CID constraints,
cancellation, unpublication, private-cache cleanup, and concurrent outstanding
wants proving that a downloaded block is never automatically forwarded.

## Vendored upstream policy patch

`vendor/beetswap` is the Apache-2.0 Beetswap 0.5.0 crate from crates.io,
SHA256 of the original crate archive:
`1a920844a4696d53e72190a8348f0048a6799647b3448d38bef90b85e0310df0`.
The exact archive checksum is also recorded under `beetswap` in the registry's
original package metadata; check it when updating. See `vendor/PATCHES.md` for
the local changes. The maintained upstream framing, codec, cryptography and
Bitswap state machines remain in use.
