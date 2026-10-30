# Bundled Tor download transport

The app ships Guardian Project's `libtor.so` 0.4.9.14 for arm64-v8a,
armeabi-v7a and x86_64. `tools/prepare_tor_runtime.py` checks the immutable
AAR SHA-256, each uncompressed library's SHA-256, size and ELF architecture,
and extracts those exact entries into `.deps/tor/lib`. The libraries have
16 KiB LOAD segment alignment. No Ricochet GUI, external Tor app or hosted
rendezvous server is required.

`PINNED.json` records artifact/source commits and binary digests. The Java 8
`org.torproject.jni.TorService` is a minimal independently written JNI ABI
binding; only the native method/field names match upstream. The upstream
modern-Java Android service and its manifest are not copied into the app. A
private Android `:bitchord_tor` service owns `TorRuntime` and the native
thread. One native Tor invocation is allowed per dedicated process, and
stopping that process must never terminate the music process.

`TorRuntime.start(File)` creates owner-only data directories and starts
loopback-only dynamically allocated SOCKS/control ports. The private
32-byte cookie is used for **SAFECOOKIE mutual challenge authentication**,
so it is never sent as a bearer credential to a listener. `TAKEOWNERSHIP`
requests daemon shutdown if the control connection disappears.
`ADD_ONION NEW:ED25519-V3 Flags=DiscardPK` creates an ephemeral onion service
on virtual port 80 directed to the private loopback accept socket. Startup
returns when local authenticated control and the onion name exist; its
`status()` reports circuit bootstrap separately. Onion identity persistence
is deliberately unnecessary because Nostr offers are refreshed per runtime.

`connect` accepts only lowercase v3 `.onion` hostnames and valid ports. It
never resolves a peer hostname through system DNS and never exposes a
clearnet proxy API. Tor checks onion address checksums; Java validates the
56-character alphabet and encoded v3 version before network activity.
SOCKS5 requires username/password authentication with random credentials
per stream and `IsolateSOCKSAuth`. Control lines, data, responses, endpoints,
concurrency and establishment deadlines are bounded. Closing interrupts
accept, pending handshakes and open streams, requests Tor shutdown and waits
up to two seconds; the isolated service owns any final process cleanup.
Application-level authenticated/resumable bulk-transfer frames run over the
returned reliable streams. Tor is a download fallback, not a streaming path.

Run local fixtures with `python native/tor/test_runtime.py`. They compile all
wrappers for Java 8 and exercise SAFECOOKIE, server impersonation rejection,
control parsing bounds, onion validation, SOCKS5 domain/authentication, all
reply address formats, bidirectional streams, inbound accept, total
handshake timeout and cancellation. Tests use loopback fixtures; they do not
load the Android library, bootstrap public Tor or contact public peers.
Physical Android bootstrap/onion reachability remains a device validation.

## Source and notices

- Guardian tor-android source: https://github.com/guardianproject/tor-android/tree/38ff08def31741a8211e3ed26af6cc1f92484e9c
- Immutable Maven artifact: URL in `PINNED.json`.
- Tor 0.4.9.14 source release: https://dist.torproject.org/tor-0.4.9.14.tar.gz
- Guardian's upstream service license: `UPSTREAM-LICENSE` (retained in full;
  it also mentions components not bundled by this adapter).
- Native dependency licenses: `licenses/`.

The Guardian source tree pins Tor submodule
`542244516d1f4e9b0769dcac18ba3e26643c3d14`, libevent
`79ddfb460847999b807cba76d04e73891f29c6ee`, OpenSSL
`45e844fa2a14ec92d146bd8f5778ac130b6625fb`, zlib
`da607da739fa6047df13e66a2af6b8bec7c2a498`, and zstd
`f8745da6ff1ad1e7bab384bd1f9d742439278e99`.
`external/cherry-pick-gp-tor-commits.sh` at the pinned Guardian source
records the exact Tor base tag and Android patch commit list. This adapter
consumes the hash-pinned published Android build rather than claiming to
reproduce its native compilation.

The exact JNI names/fields and native configuration lifecycle were checked
against `src/feature/api/org_torproject_jni_TorService.c` at ABI reference
commit `4a522a3ca491c6c04d4a44cb006b92101c42efc1` of `bitmold/tor`; this
reference is recorded separately from the hash-pinned AAR build provenance.
