# Direct UDP hole punching and QUIC downloads

This adapter implements the primary direct download transport. The app binds
one IPv4 UDP socket, performs bounded RFC 8489 STUN Binding requests through
that socket, and hands the **same retained socket** to Quinn's `Endpoint::new`.
The socket is never rebound after observing its public mapping. A send-only
clone sends mapping-opening Binding Indications toward the recipient's
candidates while the recipient starts QUIC, and sends bounded 15-second
STUN mapping keepalives. After endpoint creation, Quinn exclusively receives
all UDP packets. No separate raw STUN receive loop competes with QUIC.

The application uses existing public STUN infrastructure and Nostr relays;
it does not require a server operated for this app. STUN is address discovery;
Nostr carries encrypted connection coordination. Nostr descriptors contain
up to four numeric IPv4 candidates and the provider's exact ephemeral
certificate DER, bounded to 2048 UTF-8 bytes. They must stay in authenticated,
encrypted peer messages. Endpoints never advertise phone numbers.

Quinn 0.11.12 provides encrypted reliable streams, congestion control,
retransmission and handshake machinery. rustls 0.23.46 uses ring and an
ordinary `RootCertStore` containing only the certificate received from the
peer; there is no trust-all verifier. Certificates are generated with rcgen
0.13.2 for `bitchord.local`, with a dedicated QUIC ALPN. A one-byte internal
stream preface establishes the single bidirectional stream before exposing
it to the application. `BulkFileProtocol` must authenticate its transfer
secret before serving file bytes and retain chunk verification/resume.

`DirectClient(Context)` exposes `start`, `endpoint`, `connect`, `accept`,
`punch` and `close`. Its connection exposes `input`, `output`, `finish`, and
`close`. `finish()` half-closes output and waits up to 30 seconds for transport
acknowledgement of every queued byte and FIN. Normal protocol completion
uses application receipts, then `finish()`, then `close()`. `close()` alone
cancels immediately, interrupting pending native reads/accept/handshakes.
JNI buffers are bounded to 64 KiB and node/stream handles are registered
and reclaimed. Incoming handshakes, queued streams and QUIC pre-accept
buffers have explicit capacity limits.

The pinned mondain public STUN snapshot is in
`resources/soulseek/stun-servers.txt`, with its immutable revision and SHA-256
in `stun-servers-source.json`. The runtime reads only the bundled asset
`soulseek-native/stun-servers.txt`, parses the bounded pool and selects up to
three distinct providers for a startup; Google and Cloudflare defaults are
retained if the asset is missing or invalid. No runtime HTTP list fetch or
whole-list scan occurs. The build verifies the snapshot SHA-256;
`--refresh-stun` re-downloads only that same hash-pinned revision.

A `ConnectivityManager.getActiveNetwork()` snapshot brackets startup and
is checked before endpoint publication and network I/O. The default-network
callback closes the retained socket and streams when the active network
changes. New candidates require a fresh `DirectClient`, preventing old
Wi-Fi mappings from being reused on cellular or another network.

This is an IPv4 hole-punching implementation, **not full ICE**. There is no
TURN relay, IPv6 candidate negotiation, port prediction or promise that
symmetric/endpoint-dependent NATs will permit a direct path. Coordination
should apply a roughly five-second direct-attempt deadline and continue
with the bundled Tor/I2P/IPFS download fallbacks if it fails. It is a reliable
download path, not a low-latency streaming guarantee.

## Building and verification

Run `python tools/build_direct_native.py --offline` with the repository's
Rust/NDK setup. It builds Android API 26 libraries for arm64-v8a,
armeabi-v7a and x86_64 under `.deps/direct/lib/`, validates ELF architecture
and 16 KiB LOAD alignment, and writes SHA-256/size metadata plus all locked
transitive source license notices. `Cargo.lock` pins crate checksums;
`DEPENDENCIES.json` records the resolved dependency provenance.

Run `python native/direct/test_runtime.py` for local tests. They exercise:

- RFC 8489 transaction matching, XOR mapping, truncation and attribute bounds.
- A mocked STUN server observing the exact socket port later owned by Quinn.
- Real certificate-pinned QUIC carrying a 180 KB duplex transfer; a substituted
  certificate is rejected through rustls trust validation.
- Graceful half-close/ACK, cancellation, bounded descriptor/punch handling.
- The actual host JNI library, 64 KiB I/O, invalid handles/buffer bounds,
  accept timeout, graceful EOF and cancellation of blocked accept.

Android host fixtures additionally verify bundled STUN asset selection, explicit
fallback on missing/oversized assets, snapshot changes during discovery,
callback cancellation of blocked I/O and callback/handle cleanup. These tests
contact only local fixtures. They do not query public STUN,
bootstrap public overlay networks or send Nostr messages. Physical Android
NAT interoperability and network handover remain device validation.
