# Soulseek JNI loopback checks

Run after building the host native adapter and bootstrapping the pinned JDK and
`json.jar`:

```bash
python3 tests/soulseek-wire/run.py \
  --library native/soulseek/target/release/libbitchord_soulseek.so
```

The script compiles the actual extension's `SoulseekNative.java` and a small JVM
driver, loads the native library, and communicates with a bounded Python TCP
fixture listening only on `127.0.0.1`. No public service or real account is used.
Missing native/tooling dependencies cause a failure, not a passing skipped test.

Checks cover successful and rejected login, the actual outgoing login hash and
search frames, returned JSON, non-audio input rejection, session close while a
search is pending, and stale-handle rejection. The native Rust wire tests cover
compressed peer search replies, queue negotiation and file transfers separately.

These host checks exercise the JNI boundary. They do not establish Android JNI
loading, real-device playback, mobile NAT traversal or public-network
interoperability.
