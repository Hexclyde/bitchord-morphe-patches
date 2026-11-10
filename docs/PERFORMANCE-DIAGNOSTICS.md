# Performance diagnostics and expanded heap

This revision extends the existing optional support recorder. Host version 1.9.1 does
not identify patch code: include the patch version, source hash and build identifiers
from the exported report and the delivered verification file.

## Recording

Open patch Diagnostics, choose standard recording or detailed performance recording
(2, 5 or 15 minutes), reproduce the issue, choose **Mark issue**, then stop and export.
Detailed recording expires automatically. Standard recording retains the existing
lightweight lifecycle/error events. Detailed recording adds explicit operation handles,
not thread-local correlation. Export/compression and persistence run off interactive
threads. The writer queue holds at most 128 tasks; loss is counted. The in-memory
journal holds at most 512 records / 128 KiB; two append-only disk segments each have a
128 KiB ceiling. Rotation discards old events. A long busy recording is a bounded
recent window, not a guarantee of retaining every event for 15 minutes.

The ZIP contains readable diagnostics, validated `events.jsonl`, `timeline.csv` and
`performance-summary.txt`. Unfinished operations remain visible as starts or explicit
INCOMPLETE outcomes. Missing starts/ends can also result from rotation or dropped
records; the report must not infer success from absence of an error.

## Typed schema

`performance.span`, schema_version 1, has recording (UUID), operation, span, parent,
stage, outcome, monotonic_ns, elapsed_ns, queue_ns, bytes and thread_id. IDs are scoped
to recording/process lifetime, not durable track or Telegram identities. START,
COMPLETE, CANCELLED, SUPERSEDED, TIMEOUT, FAILED, WAITING, DEFERRED and INCOMPLETE are
allowlisted outcomes. Numeric measurements are separately allowlisted in
PerformanceTrace; unsupported fields are stripped by recording and export validation.
No arbitrary URLs, queries, message text, exception messages or credentials belong in
these events. Legacy sanitized failure events remain available. Structured arbitrary
stack traces and per-action exception cause chains are not provided by this schema.

Elapsed time is inclusive. Parallel child spans must not be summed as sequential time.
Queue timing for search requests starts at submission. Operation handles cross executor
boundaries explicitly. Range reads, TDLib progress and host seek observations currently
have separate roots: they cannot yet be joined into a complete causal seek graph.

## Coverage and limits

| Area | Implemented observation | Limit / unsupported internals |
|---|---|---|
| Host play, pause, prepare, skip, queue | Recognized Media3 command call sites in host bytecode | Command receipt, not user intent or completed playback; controls bypassing these sites are not covered |
| Seek | Single-position seek command, supersession, acceptance, READY, position-progress estimate | Indexed seek overload, gesture debounce, decoder reset, hardware audible output are not measured |
| Telegram search | Root, admission/deduplicated wait, queued chat/filter requests, completion; existing SearchReport filtering/origin | Host input debounce and results actually painted are not hooked; below the result cap the synchronous host API still waits for final results |
| Telegram reads | Reader, physical cache bytes, lease owner/wait, range wait | These are local-file reads, not internet bytes |
| Telegram download | Session/file-aware downloadedSize deltas, reset handling, prefix availability wakeups | TDLib progress is an estimate; no DNS/TLS/CDN timings exposed by TDLib |
| AutoMix | Analysis duration/outcome and required memory; existing bounded DSP stage diagnostics | Not a trace of every native model operation |
| Editor preview | Existing tap/session/deck/seek/prepare/readiness/progress/stall measurements in a typed span | Position progress is not confirmed sound at the speaker |
| Handover | Existing adoption commit or cancellation | Fingerprint success/buffering alone is not commitment |
| Memory | Startup heap flag/classes/max, Java usage, native heap, RSS, system available memory, reserve/pending/allowance; detailed samples | Metrics overlap; never add Java/native/RSS together. PSS/GC are available in the explicit device comparison, not all periodic samples |
| Backup, restore, indexing, account/library/settings | Existing status and sanitized diagnostics preserved | No new complete action-to-callback span coverage for these screens |
| HTTP transfer | Existing source diagnostics preserved; shared 429/503 backoff | DNS/connect/TLS/TTFB and range validation are not added to opaque host HTTP clients |
| Developer profiling | External Android tooling remains possible | No in-app Perfetto, sampled native stacks, method tracing or heap-dump capture added |

Encoded bitrate, player-delivered bytes, physical cache bytes and TDLib downloaded
counter deltas are different quantities. The new diagnostics never present cache read
rate as network speed. Android link bandwidth is not sampled here. Export does not
claim a generic speed test or an audible-output benchmark.

## Demonstrated changes

LiveSearch collects completed futures rather than waiting in submission order. A full
useful result set can return without a slow earlier chat. Background lookups use one
bounded worker separate from interactive request workers and yield before queued jobs
when foreground work appears. Existing selection/session validation and live search
without an index are retained.

NativeAudio reads an already validated cached range before acquiring the download
window lease. Missing ranges retain synchronization, permission checks and deadlines.
TDLib updates wake readers promptly, with a 100 ms fallback for missed/unavailable
updates. Counter baselines, resets and replacement identities prevent counting an
existing cache as newly transferred data.

Provider-wide cooldown now includes HTTP 429 and preserves server Retry-After up to
one hour, separately from the automatic exponential cooldown ceiling. The diagnostic
journal no longer rewrites its entire retained history for every saturated event.

Existing transactional source adoption, rendition ownership, accurate active-source
statistics, interactive previews, bounded analysis and scheduled backup behavior are
preserved. This change does not relax fingerprint or alignment acceptance criteria.

## Expanded Java heap

Select **Expanded Java heap** when patching. Its resource dependency modifies the
host APK's existing application element to android:largeHeap="true". An extension
manifest alone would not work. Install the newly patched APK and restart its process.
Diagnostics displays **Expanded Java heap · measured limit N MiB**, FLAG_LARGE_HEAP,
ActivityManager memory classes and Runtime.maxMemory(). Android decides N.

The managed working ceiling is min(Runtime.maxMemory(), 1024 MiB). Admission subtracts
current heap usage, unallocated pending reservations, enforced playback growth (or the
safe fallback) and safety margin. Materialized allocations are already in heap usage;
they are not reserved a second time. This does not allocate 1024 MiB, grow all caches,
raise job concurrency or use hidden ART APIs. Smaller granted heaps remain supported.

## Validation interpretation

Host-JVM and Robolectric tests are simulated. They cover slow-first search completion,
background contention, cached reads during a held lease, TDLib counter resets,
reservation ceilings, cancellation/late callbacks, journal saturation/export and seek
supersession. APK checks inspect final DEX hooks and host manifest. A device allocation
comparison measures actual runtime limits, allocation/GC and process memory; its fixed
32 MiB workload is not an AutoMix or audible-playback benchmark.

Real-account tests still need Telegram cached/uncached seeks, slow remote reads,
compressed archives, repeated editor previews, long background playback and upgrades
with both successful and failed alignment. Compare standard/expanded builds using the
same queue, cached state, output route and temperature; record analysis completion,
deferrals, GC, seek/readiness/progress estimates, underruns and process memory. A larger
heap alone does not establish faster playback or solve source/network delays.
