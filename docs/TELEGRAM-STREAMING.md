# Telegram streaming wrapper experiment

This change measures and coordinates Java download requests. `telegram_stream_v2`
defaults to **false**. It does not rebuild TDLib, change JNI or upload behavior,
request a larger Java heap, or bypass Telegram limits. The supported heap budget
remains 256 MiB. Keep the flag off until device comparisons justify enabling it.

## Verified preflight

Baseline: `testing` commit `ab6d586b3ededfd459b7f0c98ab472df15bd0dea`.
`PROVENANCE.json` pins TDLib **1.8.69**, source commit
`ea3f69ce11aef458bfbb09869b809ede4447486f`, and TDLib Android commit
`7ec241d026ee3563e615c25a700117c2abbaea19`. The native libraries are prebuilt inputs.
`bootstrap.py`, `tools/bootstrap_from_release.py` and `build.py` retain that workflow.

These findings refer to the pinned source, not current TDLib master:

| Question | Finding and source |
| --- | --- |
| Can several public `downloadFile` calls keep independent ranges? | No. A changed offset/limit cancels older pending synchronous promises: [FileManager.cpp:2977](https://github.com/tdlib/td/blob/ea3f69ce11aef458bfbb09869b809ede4447486f/td/telegram/files/FileManager.cpp#L2977). The public request changes one node window at [3103](https://github.com/tdlib/td/blob/ea3f69ce11aef458bfbb09869b809ede4447486f/td/telegram/files/FileManager.cpp#L3103); an active download is retargeted at [3248](https://github.com/tdlib/td/blob/ea3f69ce11aef458bfbb09869b809ede4447486f/td/telegram/files/FileManager.cpp#L3248). Internal TDLib consumers may ignore a public limit. Encrypted downloads are adjusted to start at zero. |
| What proves a range is readable? | `LocalFile.downloadOffset` plus `downloadedPrefixSize` describe a contiguous prefix; `downloadedSize` is a total and does not prove arbitrary range coverage. See [TdApi.java:47068](../vendor/tdlib-api/TdApi.java#L47068) and the bitmap calculation in [FileManager.cpp:1043](https://github.com/tdlib/td/blob/ea3f69ce11aef458bfbb09869b809ede4447486f/td/telegram/files/FileManager.cpp#L1043). `updateFile` has no application request ID or seek generation. |
| Can the local path change? | Yes. Completion closes the temporary file and calls `create_from_temp`: [FileDownloader.cpp:574](https://github.com/tdlib/td/blob/ea3f69ce11aef458bfbb09869b809ede4447486f/td/telegram/files/FileDownloader.cpp#L574), including the rename in [FileLoaderUtils.cpp:126](https://github.com/tdlib/td/blob/ea3f69ce11aef458bfbb09869b809ede4447486f/td/telegram/files/FileLoaderUtils.cpp#L126). Cache deletion can also invalidate a path. |
| What does a prefix query cost? | A Java/native request and TDLib actor scheduling, followed by an in-memory file view/bitmap lookup: [Requests.cpp:6494](https://github.com/tdlib/td/blob/ea3f69ce11aef458bfbb09869b809ede4447486f/td/telegram/Requests.cpp#L6494). It does not itself download payload. No per-call milliseconds were measured on a device. |
| What does `readFilePart` cost? | Java/native and actor dispatch, availability validation, disk open/size/read/close, allocation and transfer of the returned data. See [Requests.cpp:6555](https://github.com/tdlib/td/blob/ea3f69ce11aef458bfbb09869b809ede4447486f/td/telegram/Requests.cpp#L6555), [FileManager.cpp:2844](https://github.com/tdlib/td/blob/ea3f69ce11aef458bfbb09869b809ede4447486f/td/telegram/files/FileManager.cpp#L2844), [FileLoadManager.cpp:18](https://github.com/tdlib/td/blob/ea3f69ce11aef458bfbb09869b809ede4447486f/td/telegram/files/FileLoadManager.cpp#L18) and [filesystem.cpp:42](https://github.com/tdlib/td/blob/ea3f69ce11aef458bfbb09869b809ede4447486f/tdutils/td/utils/filesystem.cpp#L42). The Java API itself recommends direct local reads: [TdApi.java:141093](../vendor/tdlib-api/TdApi.java#L141093). |

Two assumptions in the original task differed from the inspected code and were
reported before implementation; work continued after authorization:

* `NativeAudio` already used `RandomAccessFile`. There were **zero** production
  `readFilePart` calls. This change reuses handles and adds that API only as recovery.
* Do not assume every CDN hash is exactly 128 KiB. The pinned downloader uses the
  hash offset and limit supplied by Telegram: [FileDownloader.cpp:404](https://github.com/tdlib/td/blob/ea3f69ce11aef458bfbb09869b809ede4447486f/td/telegram/files/FileDownloader.cpp#L404).

### Baseline call inventory

Line numbers below are at the baseline commit; repeated rows are intentional.
All active v2 download/cancel commands now pass through `TelegramStreams` and
`TelegramStreamCoordinator`. Original calls remain only in the flag-off paths.

| Baseline site | Operation | Owner |
| --- | --- | --- |
| `NativeAudio.java:264` | download | Index/archive byte-range inspection, background checks |
| `NativeAudio.java:461,498` | download | Playback, extractor seeks, editor preview; verification/analysis readers and explicit downloads share the same byte reader |
| `TelegramBridge.java:687` | download | Settings/index backup restore |
| `TelegramBridge.java:1202` | download | Shared index import |
| `TelegramBridge.java:2325` | download | Artwork |
| `TelegramBridge.java:2365` | download | Format-header probe |
| `TelegramBridge.java:2746` | download | Sponsored media |
| `DownloadUploadBridge.java:401` | download | Archive read-back/restore verification; upload calls are unchanged |
| `TelegramBridge.java:2759` | cancel | Sponsored-media release |
| `TelegramBridge.java:2823` | cancel | Final audio/background lease release |
| `DownloadUploadBridge.java:436` | cancel | Archive read-back cleanup |
| `NativeAudio.java:261,267,457,470` | prefix query | Background and foreground availability checks |
| None | readFilePart | No baseline call sites |

`TelegramDataSource` is the Media3 entry. `NativeDownloads` identifies explicit
downloads; `AudioDecode` identifies verification versus guarded analysis.
`SourceAudio` carries a consumer scope across reopen calls. Preview media IDs and
URIs are registered for the private preview decks and released with those decks.
An extractor's nonzero offset alone is not assumed to be a user seek: real player
seek notifications increment the generation and link subsequent requests to it.

## Ownership and wakeups

The coordinator owns one public download window per TDLib file ID. Registration
is passive; cached needs do not issue new downloads. A missing range elects the
highest-priority consumer. Active playback/seek ranks above preview, verification,
analysis, explicit downloads and background work. A paused main player yields its
window. Commands are sent only for changed windows, with a final ownership check
at dispatch so an obsolete planned command cannot overwrite a newer owner.

Each file has its own signal. `updateFile` copies immutable state and signals its
waiters. It performs no disk reads or synchronous TDLib calls. Waiters recheck the
exact range, using a prefix query when the reported prefix does not cover it.
Download acknowledgements and local snapshots are separate control wakeups.
Updates for another file cannot wake this file's reader. A 500 ms fallback handles
missed updates; every actual fallback is recorded after the readability check.

A real seek increments the generation and drops obsolete speculative consumers.
Closing/replacing a data source releases its old foreground registration. A seek
inside the player's existing buffer keeps that live reader usable. Download reply
serials reject old control callbacks; old `updateFile` states cannot satisfy a
different range merely because they report progress. Session changes and shutdown
drain registrations. Cancellation, errors and permission failures close the v2 reader.
Registrations and per-file bookkeeping are bounded.

`FLOOD_WAIT`, `FLOOD_PREMIUM_WAIT`, explicit retry-after messages and error 429
establish backoff shared by the coordinator's files. Cached reads remain possible.
The wrapper does not set undocumented TDLib options or change account limits.

## Buffering and local reads

The default low-water threshold is 15 seconds of encoded audio; the target is
30 seconds. Both are converted using encoded size/duration, with a **4 MiB ahead**
cap and a 64 KiB minimum. Unknown bitrate uses a bounded byte fallback. Low-water
is also limited to half the effective target so high-bitrate files cannot cause
a new command on every 64 KiB read when both time thresholds hit the byte cap.
The window extends before its edge. Earlier cached bytes need not be downloaded again.

Readers keep one reusable file handle and only read proven downloaded ranges.
They reopen on path changes, invalidate availability when the downloaded counter
shrinks, and try `readFilePart` if a file disappears or becomes truncated. Recovery
is bounded and repeats the session/permission checks. No whole audio file is held
in the Java heap. ZIP/CUE range readers retain their existing identity and access checks.

For MP4/M4A, a bounded atom-header parser identifies the declared end of a large
`mdat`, `free` or `skip` atom and primes up to three following 64 KiB ranges at the
startup consumer's priority. It does not download the gap or scan an entire file.
Fast-start `moov` needs no extra request. Hints are optional; the extractor can
request further ranges normally after a hint failure. FLAC metadata/seek tables
and MP3 Xing/VBRI headers normally arrive with the head window; this task does not
invent byte offsets or time-to-byte mappings for missing indexes. Actual range
commands are counted by detected container format, including metadata hints.

## Flag and diagnostics

Open Telegram setup → Advanced → Playback → **Experimental Telegram streaming**.
Stop playback and let outstanding reads finish before changing it. The persisted
key is `telegram_stream_v2`; absence means false. **Streaming buffer threshold**
offers 5/10/15/20/30 seconds, persisted as `telegram_stream_low_water_ms`; the target
is twice that threshold. The byte cap and fallback are bounded coordinator defaults.

The existing opt-in Diagnostics recorder, its schema allowlist, queue, journal and
export are reused. It remains limited to 512 records / 128 KiB; export each short
benchmark trial promptly. These events do not export filenames, paths, chat IDs,
TDLib file IDs, URLs or credentials. `file_ref` is an ephemeral local sequence.
Progress deltas are aggregated at most four times per second per file and flushed
on request completion/reset. Repeated cache reads do not increment download counters.

| Field/event | Meaning |
| --- | --- |
| `telegram.stream.session` | Runtime TDLib version plus `wrapper_version`, build-time git commit and source fingerprint; flag setting |
| `request_id`, `parent_request_id`, `stream_generation`, `file_ref` | Request, observed player seek and file correlation; no server identifiers |
| `offset_bytes`, `length_bytes`, `consumer`, `priority`, `stream_v2` | Requested byte range and owning feature; unknown/whole length may be zero |
| `first_byte_ms`, `required_range_ms` | Open/request to first readable byte and full first required read range; not necessarily a whole track |
| `before_progress_ms` | Time to the first observed increase of that file's downloaded counter, which may serve several consumers |
| `after_progress_ms`, `range_after_progress_ms` | Observed progress to first byte / full range; `-1` if unavailable or observations arrive in the opposite order |
| `new_bytes_estimate`, `interval_ms`, `progress_reset` | File counter delta once per file, observation interval and counter reset marker. This estimates payload, not protocol overhead or link capacity |
| `cache_bytes`, `uncached_bytes` | Bytes served that were / were not readable at the request's first availability check. These are read counts and may repeat; neither is a network byte counter |
| `poll_wakes`, `update_wakes`, `control_wakes`, `unreadable_wakes` | Legacy polls / v2 fallbacks, actual file-update wakes, acknowledgements/local-state wakes and wakes still lacking the exact range |
| `telegram.stream.wake` | Each actual fallback firing and the result of its range check |
| `telegram.stream.window`, `container_format`, `range_kind` | Actual download commands; normal reads versus `container-prefetch` |
| `prefix_calls`, `handle_opens`, `part_fallbacks`, `retry_after_ms` | Availability RPCs, handle reuse/reopen evidence, recovery reads and enforced backoff |
| `telegram.stream.output`, `output_signal`, `output_supported`, `output_ms` | Player seek/readiness and exposed sink advancement. READY always has unknown output latency. Analytics methods may be unavailable after host obfuscation |
| `buffer_ms`, `underruns`, `rebuffer_count` | Exposed player buffering and sink underruns; absent buffering is unknown, not zero |
| `heapUsedMiB`, `heapMaxMiB`, `process_cpu_ms` on `sample` signals | Roughly one-second samples during active playback with Diagnostics enabled. CPU is cumulative process time, not a download-thread measurement |

The encoded codec bitrate and these download observations are separate. Total
request duration is never called network RTT. A progress delta cannot separate
connection setup, file-reference refresh, CDN/hash work, or server queuing. The
first `updateFile` may already include a completed native part, so the observed
split is not a measurement of that part's actual on-wire transfer time.

The player-reported seek discontinuity is the seek start signal, not a UI tap
timestamp. `onAudioPositionAdvancing`, when exposed, is an audio-sink proxy, not a
measurement at a speaker/Bluetooth device. Unavailable output timing stays `-1`.
Never substitute decoder initialization or `STATE_READY` for audio output.
Player samples continue when source identity is unavailable; `source_scope` is
then `unconfirmed` and no Telegram file is attributed. The harness separates
confirmed Telegram seek samples from general player seek samples. Host metadata
or an advertised quality label alone is not evidence of the audible source.

## Reproducible comparison

No authorized device or Telegram account is configured in the build environment.
Device startup, seek latency, rebuffers, excess bytes, CPU and heap comparisons
remain **not measured**. Unit-test timings and synthetic harness fixtures are not
device benchmarks. No native scheduler change is justified by these tests alone.

1. Build once using `python3 build.py`. Keep that exact bundle, device, account,
   selected channels, power/thermal state, network and track IDs for both settings.
   Confirm the session build fingerprint and runtime TDLib version in every export.
2. Select one ordinary MP3, a FLAC (including high bitrate), a fast-start M4A and
   an M4A with trailing `moov`. Confirm each selected rendition; source verification
   can otherwise change what is played. Test preview separately from main playback.
3. For each file, perform at least ten trials per mode, alternating off/on order.
   Separate cold cache trials (stop readers, clear **Telegram audio cache** using
   the existing setting) from warm cache trials (repeat the identical range).
   Do not delete TDLib's database or session. Record which ranges were warmed.
4. Start Diagnostics immediately before the trial; play, then perform fixed seeks
   to 10%, 70%, 25% and 90%, allowing audio after each. Export after each short trial.
   Repeat with preview/analysis/indexing active to test contention, and rapid seeks
   to check old-generation cancellation. Keep the comparison workload identical.
5. Repeat on a controlled slow/high-latency profile, e.g. Android Emulator
   `adb -s SERIAL emu network speed 512:1024` and
   `adb -s SERIAL emu network delay 200:300`. Verify actual emulator semantics and
   connectivity on the installed SDK. Restore using `speed full` / `delay none`.
   A physical-device Wi-Fi/AP shaper with documented limits is another option.
   A 200 Mbps speed-test result is not this app's observed TDLib throughput.
6. Compare exported reports (or extracted JSON-line text):

   ```bash
   python3 tools/benchmark_telegram_stream.py \
     --baseline off-cold.zip --candidate on-cold.zip \
     --output build/telegram-cold-comparison.json
   ```

   Run the same command separately for warm cache, each format, each profile and
   preview. The harness rejects mixed flag settings, deduplicates request summaries,
   and reports p50/p95 first/full readability, the observed progress split, sink
   seek proxy, counter throughput, wakeups, commands per format, CPU and peak heap.
   Missing values stay null. Commands issued before exposed sink signals are an
   observed count, not a claim that each speculative range was necessary.
7. For actual seek-to-audio, synchronize a screen/tap recording with captured audio
   on a known output route. For exact excess bytes, use an isolated network/device
   capture plus the consumed byte ranges. Public TDLib totals do not expose the
   complete per-request segment/transport history; the harness intentionally leaves
   exact excess bytes and physical output latency null. Do not derive them from
   repeated reads, encoded bitrate, or file size divided by song duration.
8. Compare the wait before first observed progress with progress-to-readability.
   Investigate setup/reference refresh/CDN/rate limiting if the former dominates.
   Only consider a separate native task after device evidence shows initial-part
   transfer is a large latency component, using additional native traces if needed.

Useful external validation is `adb shell dumpsys meminfo PACKAGE` and repeated
`adb shell top -b -n 1 -p PID` samples during the same trials. Keep raw device dumps
local; this task does not add them to sanitized diagnostics exports.

## Checks

```bash
python3 tests/telegram-stream/run.py
python3 build.py
python3 test.py
python3 -m unittest discover -s tests -p 'test_*.py'
python3 ui-tests/run.py -DreuseForks=false
```

The isolated scripted tests need no Android device, JNI or Telegram account. The
default host suite also exercises the actual byte reader against temporary local
files: reusable handles, completion moves, partial/warm cache, eviction/fallback,
revocation and cleanup. The UI suite checks the default-off control and uses the
real Media3 analytics interface to verify READY is not output evidence and the
new fields survive the existing report export. See `TELEGRAM-STREAMING-VALIDATION.md`
for the concrete environment, commands, results and artifact identity.
