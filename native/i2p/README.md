# Embedded I2P runtime

The adapter embeds maintained PurpleI2P i2pd 3.0.0 and uses its native reliable
stream API directly. It does not start SAM, SOCKS, HTTP, BOB, I2CP or router-control
listeners. Router transport sockets connect to public I2P when the app explicitly
starts the runtime.

```bash
python3 tools/build_i2p_native.py
python3 -m unittest discover -s native/i2p/tests -v
```

The build supports `--abi arm64-v8a`, `--abi armeabi-v7a`, `--abi x86_64`,
`--offline` and at most two compilation jobs. Set `ANDROID_NDK_HOME` to a full
Android NDK or its LLVM prebuilt directory; `.deps/ndk` is detected automatically.
Outputs are API 26 JNI shared libraries with static OpenSSL/libc++, hidden
non-JNI symbols, immediate binding, RELRO and 16 KiB ELF segment alignment:

```
.deps/i2p/lib/<abi>/libbitchord_i2p.so
.deps/i2p/assets/certificates/{reseed,family}/*.crt
.deps/i2p/build-hashes.json
.deps/i2p/runtime-manifest.json
.deps/i2p/licenses/{i2pd,boost,openssl}.txt
```

`sources.json` pins upstream revisions, dependency versions and archive hashes.
Every build verifies cached archive hashes and prepared source tree hashes.
Extraction rejects links, traversal, duplicate members, missing interfaces and
excessive expansion. OpenSSL is built from its pinned source for each ABI. i2pd
uses C++20 standard filesystem and Boost headers/program_options; unused client
proxy, web UI, JSON/URL API and daemon components are not linked.

`I2pNative` must load only in the app's private `:bitchord_i2p` process. The Android
service installs bundled certificates into an app-private no-backup home before
`start`. SU3 reseed signature verification is explicitly enabled. Router transit
sharing and floodfill are disabled. Destination keys use Ed25519/X25519, persist
atomically with mode 0600, and produce a stable base32 destination.

The Java API exposes start/status/localDestination, accept/connect, read/write,
closeStream and stop. Deadlines are 1–120000 ms; each I/O call accepts at most
65536 bytes. Reads return -1 on EOF and 0 on deadline. A deadline closes its
stream so an overlapping abandoned receive cannot consume a later request.
Writes throw on deadline or failure. The service resumes downloads by opening a
fresh stream and requesting a verified byte range. JNI registries and the
incoming queue are bounded, close is idempotent, and stop cancels operations and
joins upstream workers. A router can initialize only once per process; after
stop or startup failure the service terminates its own private process.

I2P can transfer files only when both paired phones run the overlay and serve
those files. It cannot reach ordinary Soulseek peers. The JNI boundary tests use
an explicitly fake in-memory backend without opening network sockets; they cover
byte transfer, validation, deadlines, EOF, cancellation and process lifecycle
restrictions. Android cross-compilation and ELF checks do not demonstrate device
execution or successful transfers across the public I2P network.
