# On-phone Soulseek native adapter

This development library wraps the pinned `soulseek-rs-lib` protocol engine for
`app.bitchord.soulseek.SoulseekNative`. It opens direct network sockets from the
phone. It has no project-hosted backend or embedded Node/.NET runtime. Ordinary
Soulseek operation still needs the existing Soulseek server/peer network; the
separate opt-in coordination and discovery modules use public Nostr relays.

The Java host must call blocking login/search and `close` from an IO executor.
`download` queues a transfer; `transfers` returns bounded JSON snapshots. Only
supported audio filenames are accepted, and initial staging is limited to 256 MiB.
These filename checks are not proof of audio content: the runtime must decode or
inspect the original before claiming a verified format or quality improvement.

Library name: `bitchord_soulseek` (`libbitchord_soulseek.so`). Host crate also emits
an `rlib` for loopback protocol tests. JNI symbols match the un-obfuscated Java
class name and must be preserved in packaging.

## Lifecycle

Handles are integer IDs in a registry, never raw pointers passed to Java.
Closing invalidates the ID, reserves its home, cancels searches/login, prevents new file workers,
cancels unfinished transfers, disconnects and waits for existing workers to leave.
A close timeout reports an error and keeps the home quarantined: callers must
retain staging data rather than assuming it is safe to remove. A later close
can retry the drain; creating another session for that home is blocked until
teardown succeeds. Repeated close is harmless.

Each file has a private directory bound to peer/path/expected-size. Different
peers with the same basename cannot overwrite one another. Explicit cancellation
may discard a partial; retry after cancellation gets a new directory/ID. A failed
transfer can resume its own partial. Local staging identity does not authenticate
a remote file, so bytes from different peers are never spliced together.

Snapshots expose `downloaded` contiguous bytes confirmed by the transfer engine,
not sparse-file length. `complete` is true only when the native engine reports
completion and the physical file has exactly the expected length. A rename can
briefly leave an in-progress snapshot with an empty path; retry rather than EOF.
Leases across playback/download/upload remain the Java host's responsibility.

The adapter suppresses upstream logs in embedded builds and throws redacted
Java exceptions. It never writes credentials to disk. Credentials do remain in
session memory while the upstream client is alive; no secure-memory guarantee
is claimed. The server and peer protocol is not made end-to-end encrypted by JNI.

## Adaptive search and compatible-peer discovery

`search` preserves the full-window API. `searchAdaptive` adds a preferred-extension
hint and returns a settled initial batch once the highest enabled format arrives,
without shortening the no-preferred-result budget. Its result cap retains
preferred-format candidates; downloaded bytes remain subject to Java's codec
verification and exact selected-source authorization.

`PeerDiscoveryNative` uses signed Nostr replaceable kind `11120` for public
`app.bitchord.providers/1` profiles. A read-only session subscribes for ten
seconds and publishes nothing. An explicitly advertising session renews only its
own profile until closed. Records contain public app identity, protocol and
transport capability names with an expiry of at most five minutes. Strict schema,
signature, timestamp, frame, verification and queue limits apply. No filenames,
network endpoint descriptors or private sharing capabilities enter these records.
The Java host treats returned keys as untrusted candidates and owns consent,
cache, lifecycle and paired-only PEX. See [the discovery guide](../../docs/PEER-DISCOVERY.md).

## Build and test

Use Rust 1.91 or newer. From the repository root:

```sh
cargo test --manifest-path native/soulseek/Cargo.toml --locked
cargo build --manifest-path native/soulseek/Cargo.toml --release --locked
```

Cross builds require the matching Rust Android target and an Android NDK API26
linker. The packaging script stages libraries under `.deps/soulseek/<abi>/` for
arm64-v8a, armeabi-v7a and x86_64. A successful cross build is not a real-device test.

`tests/wire.rs` uses loopback fixtures for rejected/successful login, real compressed
search responses, audio-only filtering, peer queues/cancellation, actual progressive
file bytes/completion and close during search. No public account is used.
The parent repository also has a JVM JNI harness in `tests/soulseek-wire/`.

The upstream library's default identifier is its designated unidentified embedding
ID. Reserve a project-specific client minor before public distribution. This
experimental adapter does not silently borrow Seeker's identifier.

Upstream license and local modifications are recorded in
`vendor/soulseek-rs-lib/PROVENANCE.json`. Dependency versions are pinned in the
adjacent `Cargo.lock`; native bundles must include all transitive dependency notices.
