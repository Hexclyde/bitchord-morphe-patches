# Telegram wrapper validation — 10 November 2026

## Artifact and scope

Branch: `fix/telegram-wrapper-latency-preflight`, based on `testing` commit
`ab6d586b3ededfd459b7f0c98ab472df15bd0dea`. No merge or release publication was done.

The delivered bundle was built from code commit
`2833ac33945f94e862a10ae370b46709b3a960ac` with a clean working tree. A later
report/test-formatting commit adds this report; it does not change the bundle's packaged code.

| Item | Value |
| --- | --- |
| Wrapper version | `java-stream-1` |
| Baked build identifier | `1.1.0-beta.19 commit=2833ac33945f source=ff050d41da558735` |
| Build output | `build/bitchord-patches-1.1.0-beta.19.mpp` |
| Download copy | `bitchord-patches-1.1.0-beta.19-telegram-stream-v2.mpp` |
| Bundle bytes | 77,480,018 |
| SHA-256 | `5f6406a95e50da0793c21b830d3d678cc0d245a0f288a541a610a91910277431` |
| Feature flag | `telegram_stream_v2`, default **off** |
| Native comparison | All **30** packaged `.so` files identical to the released beta19 bundle |

TDLib, TdApi, JNI, native source, `bootstrap.py`, `build.py` and `PROVENANCE.json`
were not modified. The Java heap was not enlarged. The comparison covers all
three packaged ABIs: `arm64-v8a`, `armeabi-v7a` and `x86_64`.

## Verified TDLib facts

The bundled version is **1.8.69**; its source pin is
`ea3f69ce11aef458bfbb09869b809ede4447486f`. The Android build pin is
`7ec241d026ee3563e615c25a700117c2abbaea19`. These were read from provenance and
checked against the pinned source and generated bindings.

| Fact | File/line evidence |
| --- | --- |
| Changed public offset/limit replaces the public window and cancels incompatible pending synchronous promises | [FileManager.cpp:2977](https://github.com/tdlib/td/blob/ea3f69ce11aef458bfbb09869b809ede4447486f/td/telegram/files/FileManager.cpp#L2977), [3103](https://github.com/tdlib/td/blob/ea3f69ce11aef458bfbb09869b809ede4447486f/td/telegram/files/FileManager.cpp#L3103), [3248](https://github.com/tdlib/td/blob/ea3f69ce11aef458bfbb09869b809ede4447486f/td/telegram/files/FileManager.cpp#L3248) |
| Downloaded total is not arbitrary-range proof; offset/prefix or bitmap lookup is required | [TdApi.java:47068](../vendor/tdlib-api/TdApi.java#L47068), [FileManager.cpp:1043](https://github.com/tdlib/td/blob/ea3f69ce11aef458bfbb09869b809ede4447486f/td/telegram/files/FileManager.cpp#L1043) |
| Completion can move the local path | [FileDownloader.cpp:574](https://github.com/tdlib/td/blob/ea3f69ce11aef458bfbb09869b809ede4447486f/td/telegram/files/FileDownloader.cpp#L574), [FileLoaderUtils.cpp:126](https://github.com/tdlib/td/blob/ea3f69ce11aef458bfbb09869b809ede4447486f/td/telegram/files/FileLoaderUtils.cpp#L126) |
| Prefix query: JNI/actor request plus local file-view/bitmap calculation; no payload fetch | [Requests.cpp:6494](https://github.com/tdlib/td/blob/ea3f69ce11aef458bfbb09869b809ede4447486f/td/telegram/Requests.cpp#L6494) |
| `readFilePart`: dispatch, availability validation, allocation and disk open/read/close, then returned-data transfer | [Requests.cpp:6555](https://github.com/tdlib/td/blob/ea3f69ce11aef458bfbb09869b809ede4447486f/td/telegram/Requests.cpp#L6555), [FileManager.cpp:2844](https://github.com/tdlib/td/blob/ea3f69ce11aef458bfbb09869b809ede4447486f/td/telegram/files/FileManager.cpp#L2844), [filesystem.cpp:42](https://github.com/tdlib/td/blob/ea3f69ce11aef458bfbb09869b809ede4447486f/tdutils/td/utils/filesystem.cpp#L42) |
| Hash sizes are server-supplied; do not universally assume 128 KiB | [FileDownloader.cpp:404](https://github.com/tdlib/td/blob/ea3f69ce11aef458bfbb09869b809ede4447486f/td/telegram/files/FileDownloader.cpp#L404) |

Per-call milliseconds and runtime TDLib's `GetOption("version")` response were not
measured here. The new session event captures the runtime response on device.
The baseline already read local files directly and had zero `readFilePart` calls.
Those differences from the original assumptions were reported before continuing.
The complete baseline call inventory is in [the implementation guide](TELEGRAM-STREAMING.md#baseline-call-inventory).

## Commands and results

Environment: Linux x86-64, Python 3.12, pinned Temurin JDK 21.0.13+9, Maven 3.9.9,
google-java-format 1.24.0 and Black 26.5.1. Native dependencies came from the
released beta19 `.mpp`; build tools were downloaded with the repository's bootstrap
helpers. No Telegram account, actual music file or authorized Android device was used.

Dependency preparation used these helper calls, without native compilation:

```python
from pathlib import Path
import json
from tools.bootstrap_from_release import download_tools, stage_native
download_tools(Path('.deps/tools'), json.load(open('PROVENANCE.json')))
stage_native(Path('.deps/baseline-beta19.mpp'), Path('.deps/tools'))
```

The final checks ran from the repository root:

```bash
python3 build.py
python3 test.py
python3 tests/telegram-stream/run.py
python3 -m unittest discover -s tests -p 'test_*.py'
python3 -m unittest discover -s tests -p test_telegram_stream_benchmark.py
python3 tools/check_release.py
git diff --exit-code ab6d586 -- PROVENANCE.json bootstrap.py build.py vendor/tdlib-api native
```

| Check | Result |
| --- | --- |
| `build.py` | PASS; `BUILD_EXIT=0`, bundle identity above |
| `test.py` | PASS; `HOST_EXIT=0`, including all existing host suites and the new streaming tests |
| Isolated streaming runner | PASS: 171 diagnostic privacy/classification assertions, request/progress/export tests, scripted coordinator tests and container/consumer-scope tests |
| New capture-comparison Python tests | PASS: 3 tests; synthetic aggregation fixtures, not device measurements |
| Full Python discovery | 58/59 passed; existing translation failure reproduced on the untouched base, below |
| Repository/source checks | PASS |
| Changed Java / Python formatting | PASS |
| Native input/source diff | PASS; no changes in the constrained paths |
| Bundle-native comparison | PASS; all 30 `.so` names and SHA-256 values match the beta19 input |

The actual reader tests include warm and partial cache, completion rename, missing
or truncated local files, `readFilePart` recovery, permission revocation, a session
change during range readiness, ZIP metadata limits, pool cleanup, and three
interactive readers remaining available beside analysis and verification.
Coordinator tests cover competing consumers, per-file signals, false readiness,
missed updates, stale callbacks, rapid seeks, cached readiness guard races,
time-based read-ahead, high-bitrate cap behavior, backoff across files and lifecycle
cleanup. The legacy path retains its 97 existing reader/cache checks.

Explicit 256 MiB host validation used the same classpath ordering as `test.py`:

```bash
TEST_CP='build/host-tests:.deps/tools/json.jar:.deps/tools/android.jar:build/extension-classes:.deps/tools/annotation.jar:build/compile-api.jar:.deps/tools/morphe-desktop-1.19.1-all.jar'
.deps/tools/jdk-21.0.13+9/bin/java -Xmx256m -cp "$TEST_CP" app.bitchord.telegram.NativeAudioV2Test
.deps/tools/jdk-21.0.13+9/bin/java -Xmx256m -cp "$TEST_CP" app.bitchord.telegram.NativeAudioTest
```

Both passed. This validates the wrapper fixtures under the cap; it is not a device
heap profile of every BitChord feature running simultaneously. Put `json.jar`
before `android.jar` in manual host commands to avoid Android's stub JSONObject.

### UI checks and environment limits

The full UI run executed 1,656 cases: 1,653 passed, with one existing AudioOrigin
failure and two framework-download initialization failures. The framework failures
were recovered in targeted runs. Loading several Android frameworks in one JVM
also triggered environment SIGKILL/137 failures, so the setup-screen SDK cases
were run in separate JVMs. No test assertions were removed or relaxed.

| Targeted checks | Result |
| --- | --- |
| Setup screen SDK 35 | 19 passed, including default-off toggle and configurable threshold |
| Setup screen SDK 26–34 and 36 | One relevant case per SDK passed in isolated runs; 29 setup-screen cases total across SDK 26–36 |
| Native Media3 adapter | 9 passed across SDK 26/35/36 |
| Existing local-playlist framework cases | 15 passed after dependency recovery |
| New real-Media3 analytics-interface test | PASS; READY remains unknown, sink advancement is separately recorded, fields survive export, unconfirmed sources receive no Telegram file attribution |

UI staging used `python3 ui-tests/run.py`. After staging/compilation, isolated
reruns used the following Maven commands with JDK 21 and `_JAVA_OPTIONS=-Xmx512m`
(the test JVM only; no app heap setting changed):

```bash
mvn -B -f ui-tests/pom.xml surefire:test -DreuseForks=false -Dtest=TelegramStreamPlaybackTest
mvn -B -f ui-tests/pom.xml surefire:test -DreuseForks=false -Dtest=TelegramScreenTest -Drobolectric.enabledSdks=26
mvn -B -f ui-tests/pom.xml surefire:test -DreuseForks=false -Dtest=TelegramScreenTest -Drobolectric.enabledSdks=36
```

The SDK 27–35 screen runs used `mvn -B -f ui-tests/pom.xml test` with the same
test selector and one `robolectric.enabledSdks` value per run. Native Media3 and
playlist checks used `-Dtest=LocalPlaylistLibraryTest,TelegramStreamPlaybackTest,NativeMedia3Test`.
The workspace required per-process proxy/certificate arguments in addition to
these test arguments: `-s .deps/tools/maven-settings.xml`, the current
`robolectric.dependency.proxy.host`/`port`, and `javax.net.ssl.trustStore` pointing
to the workspace CA store. Those transport-only values were derived from each
process's proxy environment; they are not portable build requirements.

### Existing base failures

Both failures were reproduced in a separate worktree at the untouched base commit:

* `python3 -m unittest discover -s .deps/baseline-source/tests -p test_translations.py`:
  `test_marked_phrases_are_listed` reports 16 missing backup-related catalog phrases.
  The same failure is present in the full Python discovery on this branch.
* `python3 .deps/baseline-source/ui-tests/run.py -Dtest=AudioOriginTest`:
  33/34 passed; `adapterReadsPerEntryMappingAndFallsBackWhenOptionalRuntimeIsAbsent`
  at line 948 expects `bitchord://watch?v=abc&q=hifi-1` but receives
  `bitchord://watch?v=abc`. The branch's full UI run reproduces it.

These unrelated catalog/adapter issues were not changed. An early existing Sing
output test was transiently unsuccessful; it passed on rerun and in the final
complete host suite. The report therefore does not claim every repository-wide
check is green.

## Benchmarks and native-phase decision

| Measurement | Flag off | Flag on |
| --- | --- | --- |
| Device startup / seek latency | Not measured | Not measured |
| Wait before first TDLib progress | Not measured | Not measured |
| Progress-to-readable-range interval | Not measured | Not measured |
| Rebuffers, wire/excess bytes, device CPU and Java heap | Not measured | Not measured |

No authorized device/account was available. No throughput improvement, latency
percentage or native bottleneck conclusion is claimed from synthetic tests.
The capture-comparison harness and cold/warm/slow-network procedure are supplied
in [the implementation guide](TELEGRAM-STREAMING.md#reproducible-comparison).

On device, compare `before_progress_ms`, `after_progress_ms` and
`range_after_progress_ms`. They are an **observed file-progress split**, not
connection RTT or a direct on-wire part-transfer measurement. A file update may
arrive only after a native part has transferred/verified. Physical audio output
and exact excess network bytes remain explicitly unmeasured when unavailable.

**Decision: no Phase 2 or Phase 3 work.** Keep the flag off by default and obtain
real-device measurements before deciding whether a separate TDLib scheduler task
is justified. The delivered bundle has no native scheduler changes.
